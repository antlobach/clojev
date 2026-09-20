(ns clojev.core-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]]
            [clojev.core :as clojev]
            [clojev.http.jvm :as jvm]
            [clojev.transport :as transport])
  (:import (com.sun.net.httpserver HttpExchange HttpHandler HttpServer)
           (java.net InetSocketAddress)
           (java.nio.charset StandardCharsets)))

(defn- response-body [answers]
  (json/write-str
    {:model "jev-1.13.0"
     :answers answers
     :usage {:input_tokens 21 :output_tokens 7}}))

(defn- response-error [questions response]
  (let [client (jvm/client
                 {:api-key "test-key"
                  :request-fn
                  (fn [_]
                    {:status 200
                     :headers {"x-typesafe-request-id" "req_validation"}
                     :body (json/write-str response)})})]
    (try
      (clojev/system-one client "state" questions)
      nil
      (catch clojure.lang.ExceptionInfo error
        (ex-data error)))))

(defn- respond! [^HttpExchange exchange status body headers]
  (doseq [[name value] headers]
    (.add (.getResponseHeaders exchange) name value))
  (let [bytes (.getBytes ^String body StandardCharsets/UTF_8)]
    (.sendResponseHeaders exchange status (alength bytes))
    (with-open [stream (.getResponseBody exchange)]
      (.write stream bytes))))

(defn- with-server [handler f]
  (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext
      server
      "/v1/"
      (reify HttpHandler
        (handle [_ exchange]
          (handler exchange))))
    (.start server)
    (try
      (f (str "http://127.0.0.1:" (.getPort (.getAddress server))))
      (finally
        (.stop server 0)))))

(defrecord DeterministicPlatform [clock sleeps environment random-value]
  transport/Platform
  (-now-ms [_]
    @clock)
  (-sleep-ms! [_ milliseconds]
    (swap! sleeps conj milliseconds)
    (swap! clock + milliseconds))
  (-random-double [_]
    random-value)
  (-getenv [_ name]
    (get environment name)))

(defn- deterministic-platform []
  (let [clock (atom 0)
        sleeps (atom [])]
    {:platform (->DeterministicPlatform clock sleeps {} 0.0)
     :clock clock
     :sleeps sleeps}))

(deftest resolves-environment-configuration-and-rejects-blank-credentials
  (let [seen (promise)
        platform (->DeterministicPlatform
                   (atom 0)
                   (atom [])
                   {"TYPESAFE_API_KEY" "env-key"
                    "TYPESAFE_DEFAULT_MODEL" "jev-env"
                    "TYPESAFE_BASE_URL" "https://env.example/"}
                   0.0)
        client (jvm/client
                 {:platform platform
                  :request-fn
                  (fn [request]
                    (deliver seen request)
                    {:status 200
                     :headers {}
                     :body (response-body
                             {"ok" {:type "noul" :noul 1.0}})})})
        _ (clojev/system-one
            client "state" {:ok (clojev/noul "OK?")})
        request @seen
        payload (json/read-str (:body request))
        blank-error
        (try
          (jvm/client
            {:api-key " "
             :platform platform
             :request-fn (fn [_]
                           (throw (AssertionError. "HTTP must not run")))})
          nil
          (catch clojure.lang.ExceptionInfo error
            (ex-data error)))]
    (is (= "https://env.example/v1/systemone" (:url request)))
    (is (= "Bearer env-key"
           (get-in request [:headers "Authorization"])))
    (is (= "jev-env" (get payload "model")))
    (is (= :clojev/invalid-request (:type blank-error)))
    (is (= :api-key (:field blank-error)))))

(deftest sends-system-one-request-and-decodes-all-answer-types
  (let [seen (promise)]
    (with-server
      (fn [exchange]
        (deliver seen
                 {:method (.getRequestMethod exchange)
                  :path (.getPath (.getRequestURI exchange))
                  :authorization (.getFirst (.getRequestHeaders exchange)
                                            "Authorization")
                  :body (json/read-str (slurp (.getRequestBody exchange)))})
        (respond!
          exchange
          200
          (response-body
            {"urgent" {:type "noul" :noul 0.91}
             "team" {:type "choice"
                     :choice "billing"
                     :probabilities {"billing" 0.8 "technical" 0.2}
                     :confidence 0.75}
             "tone" {:type "score"
                     :score 1.2
                     :legend {"0" "calm" "1" "frustrated" "2" "angry"}
                     :probabilities {"0" 0.1 "1" 0.6 "2" 0.3}
                     :confidence 0.7}})
          {}))
      (fn [base-url]
        (let [client (jvm/client
                       {:api-key "test-key"
                        :base-url base-url
                        :headers {"authorization" "Bearer wrong"}})
              result (clojev/system-one
                       client
                       {:message "My payment failed"}
                       {:urgent (clojev/noul "Is this urgent?")
                        "team" (clojev/choice
                                 "Which team?"
                                 {"billing" "Payments"
                                  "technical" "Bugs"})
                        :tone (clojev/score
                                "How upset is the user?"
                                ["calm" "frustrated" "angry"])}
                       {:extra-headers {"Authorization" "Bearer also-wrong"}})
              request @seen]
          (is (= "POST" (:method request)))
          (is (= "/v1/systemone" (:path request)))
          (is (= "Bearer test-key" (:authorization request)))
          (is (= "jev-latest" (get (:body request) "model")))
          (is (= {:model "jev-1.13.0"
                  :answers
                  {:urgent {:type :noul :noul 0.91}
                   "team" {:type :choice
                           :choice "billing"
                           :probabilities {"billing" 0.8
                                           "technical" 0.2}
                           :confidence 0.75}
                   :tone {:type :score
                          :score 1.2
                          :legend {"0" "calm"
                                   "1" "frustrated"
                                   "2" "angry"}
                          :probabilities {"0" 0.1 "1" 0.6 "2" 0.3}
                          :confidence 0.7}}
                  :usage {:input-tokens 21 :output-tokens 7}}
                 result)))))))

(deftest retries-retryable-response
  (let [attempts (atom 0)]
    (with-server
      (fn [exchange]
        (if (= 1 (swap! attempts inc))
          (respond! exchange 429 "{\"error\":\"rate limited\"}"
                    {"retry-after-ms" "0"})
          (respond! exchange 200
                    (response-body
                      {"urgent" {:type "noul" :noul 0.8}})
                    {})))
      (fn [base-url]
        (let [client (jvm/client
                       {:api-key "test-key"
                        :base-url base-url
                        :retry {:max-retries 1
                                :backoff-initial-ms 0}})]
          (is (= 0.8
                 (get-in (clojev/system-one
                           client "now" {:urgent (clojev/noul "Urgent?")})
                         [:answers :urgent :noul])))
          (is (= 2 @attempts)))))))

(deftest exposes-structured-api-errors
  (with-server
    (fn [exchange]
      (respond! exchange 422
                "{\"detail\":{\"field\":\"questions\"}}"
                {"x-typesafe-request-id" "req_test"}))
    (fn [base-url]
      (let [client (jvm/client
                     {:api-key "test-key"
                      :base-url base-url
                      :retry {:max-retries 0}})
            error (try
                    (clojev/system-one
                      client "state" {:valid (clojev/noul "Valid?")})
                    (catch clojure.lang.ExceptionInfo error error))]
        (is (= :clojev/unprocessable-entity (:type (ex-data error))))
        (is (= 422 (:status (ex-data error))))
        (is (= "req_test" (:request-id (ex-data error))))
        (is (= {"detail" {"field" "questions"}}
               (:body (ex-data error))))))))

(deftest swaps-jvm-http-client-with-request-function
  (let [request (promise)
        client (jvm/client
                 {:api-key "test-key"
                  :request-fn
                  (fn [value]
                    (deliver request value)
                    {:status 200
                     :headers {}
                     :body (response-body
                             {"route" {:type "choice"
                                       :choice "fast"
                                       :probabilities {"fast" 1.0}
                                       :confidence 1.0}})})})
        result (clojev/system-one
                 client
                 "state"
                 {:route (clojev/choice "Route?" {"fast" nil})})]
    (is (= :post (:method @request)))
    (is (= "fast" (get-in result [:answers :route :choice])))))

(deftest validates-question-boundaries-before-http
  (testing "score requires the documented two-level minimum"
    (let [error (try
                  (clojev/score "Rate it" ["only"])
                  (catch clojure.lang.ExceptionInfo error error))]
      (is (= :criteria (:field (ex-data error))))))
  (testing "choice enforces the documented 255-option maximum"
    (let [criteria (into {} (map (fn [index] [(str index) nil]) (range 256)))
          error (try
                  (clojev/choice "Choose" criteria)
                  (catch clojure.lang.ExceptionInfo error error))]
      (is (= :criteria (:field (ex-data error)))))))

(deftest sends-every-structured-state-and-question-shape
  (let [requests (atom [])
        client (jvm/client
                 {:api-key "test-key"
                  :request-fn
                  (fn [request]
                    (swap! requests conj (json/read-str (:body request)))
                    {:status 200
                     :headers {}
                     :body
                     (response-body
                       {"truth" {:type "noul" :noul 0.6}
                        "route" {:type "choice"
                                 :choice "alpha"
                                 :probabilities {"alpha" 0.7 "beta" 0.3}
                                 :confidence 0.8}
                        "rating" {:type "score"
                                  :score 1.25
                                  :legend {"0" "low"
                                           "1" {"label" "medium"}
                                           "2" ["high"]}
                                  :probabilities {"0" 0.1
                                                  "1" 0.55
                                                  "2" 0.35}
                                  :confidence 0.75}})})})
        questions
        {:truth (clojev/noul
                  {"prompt" ["Is this true?" {"context" true}]}
                  {:true {"meaning" "yes"}
                   :false ["no"]})
         :route (clojev/choice
                  ["Choose" {"using" "evidence"}]
                  {"alpha" nil
                   "beta" {"reason" ["fallback" false]}})
         :rating (clojev/score
                   "Rate it"
                   ["low" {"label" "medium"} ["high"]])}
        states ["text state"
                {"nested" ["state" {"active" true}]}
                ["state" {"count" 2}]]]
    (doseq [state states]
      (is (= #{:truth :route :rating}
             (set (keys (:answers
                          (clojev/system-one client state questions)))))))
    (is (= states (mapv #(get % "state") @requests)))
    (is (= {"true" {"meaning" "yes"}
            "false" ["no"]}
           (get-in (first @requests)
                   ["questions" "truth" "criteria"])))
    (is (= {"alpha" nil
            "beta" {"reason" ["fallback" false]}}
           (get-in (first @requests)
                   ["questions" "route" "criteria"])))
    (is (= ["low" {"label" "medium"} ["high"]]
           (get-in (first @requests)
                   ["questions" "rating" "criteria"])))))

(deftest rejects-non-structured-state-and-instructions-before-http
  (let [attempts (atom 0)
        client (jvm/client
                 {:api-key "test-key"
                  :request-fn
                  (fn [_]
                    (swap! attempts inc)
                    (throw (AssertionError. "HTTP must not run")))})]
    (doseq [state [nil
                   42
                   true
                   #{1 2}
                   {:nested #{1 2}}
                   {:nested 'symbol}
                   {:same 1 "same" 2}
                   {:nested ##NaN}]]
      (let [error (try
                    (clojev/system-one
                      client state {:ok (clojev/noul "OK?")})
                    (catch clojure.lang.ExceptionInfo error error))]
        (is (= :clojev/invalid-request (:type (ex-data error))))
        (is (= :state (:field (ex-data error))))))
    (doseq [instructions [nil
                          42
                          true
                          #{1 2}
                          {:nested #{1 2}}
                          {:nested 'symbol}
                          {:same 1 "same" 2}
                          {:nested ##Inf}]]
      (let [error (try
                    (clojev/system-one
                      client
                      "state"
                      {:ok {:type "noul"
                            :instructions instructions}})
                    (catch clojure.lang.ExceptionInfo error error))]
        (is (= :clojev/invalid-request (:type (ex-data error))))
        (is (= :instructions (:field (ex-data error))))))
    (is (zero? @attempts))))

(deftest rejects-malformed-raw-question-maps-before-http
  (let [attempts (atom 0)
        client (jvm/client
                 {:api-key "test-key"
                  :request-fn
                  (fn [_]
                    (swap! attempts inc)
                    (throw (AssertionError. "HTTP must not run")))})
        malformed
        [["question value" "not a map" :questions]
         ["missing instructions" {:type "noul"} :instructions]
         ["unknown primitive" {:type "other"
                                :instructions "Question?"} :type]
         ["noul criteria key" {:type "noul"
                               :instructions "Question?"
                               :criteria {:maybe "unknown"}} :criteria]
         ["noul criteria value" {:type "noul"
                                 :instructions "Question?"
                                 :criteria {:true 1}} :criteria]
         ["choice option name" {:type "choice"
                                :instructions "Question?"
                                :criteria {:keyword "description"}} :criteria]
         ["choice option description" {:type "choice"
                                       :instructions "Question?"
                                       :criteria {"option" 1}} :criteria]
         ["score level" {:type "score"
                         :instructions "Question?"
                         :criteria ["low" 1]} :criteria]
         ["foreign field" {:type "noul"
                           :instructions "Question?"
                           :choice-options {"yes" nil}} :questions]]]
    (doseq [[label question field] malformed]
      (testing label
        (let [error (try
                      (clojev/system-one client "state" {:q question})
                      (catch clojure.lang.ExceptionInfo error error))]
          (is (= :clojev/invalid-request (:type (ex-data error))))
          (is (= field (:field (ex-data error)))))))
    (is (zero? @attempts))))

(deftest rejects-question-ids-that-collide-on-the-wire
  (let [client (jvm/client
                 {:api-key "test-key"
                  :request-fn
                  (fn [_]
                    (throw (AssertionError. "HTTP must not run")))})
        error (try
                (clojev/system-one
                  client
                  "state"
                  (array-map
                    :same (clojev/noul "First?")
                    "same" (clojev/noul "Second?")))
                (catch clojure.lang.ExceptionInfo error error))]
    (is (= :clojev/invalid-request (:type (ex-data error))))
    (is (= :questions (:field (ex-data error))))))

(deftest applies-per-call-model-timeout-and-header-overrides
  (let [seen (promise)
        client (jvm/client
                 {:api-key "test-key"
                  :model "jev-client-default"
                  :headers {"X-Client" "client"}
                  :request-fn
                  (fn [request]
                    (deliver seen request)
                    {:status 200
                     :headers {}
                     :body (response-body
                             {"ok" {:type "noul" :noul 1.0}})})})
        result (clojev/system-one
                 client
                 "state"
                 {:ok (clojev/noul "OK?")}
                 {:model "jev-call-override"
                  :timeout-ms 1234
                  :extra-headers
                  {"X-Call" "call"
                   "authorization" "Bearer wrong"}})
        request @seen
        payload (json/read-str (:body request))]
    (is (= 1.0 (get-in result [:answers :ok :noul])))
    (is (= 1234 (:timeout-ms request)))
    (is (= "jev-call-override" (get payload "model")))
    (is (= "client" (get-in request [:headers "X-Client"])))
    (is (= "call" (get-in request [:headers "X-Call"])))
    (is (= "Bearer test-key"
           (get-in request [:headers "Authorization"])))
    (is (nil? (get-in request [:headers "authorization"])))))

(deftest rejects-invalid-per-call-retry-before-http
  (let [attempts (atom 0)
        client (jvm/client
                 {:api-key "test-key"
                  :request-fn
                  (fn [_]
                    (swap! attempts inc)
                    (throw (AssertionError. "HTTP must not run")))})
        error (try
                (clojev/system-one
                  client
                  "state"
                  {:ok (clojev/noul "OK?")}
                  {:retry :invalid})
                (catch clojure.lang.ExceptionInfo error error))]
    (is (= :clojev/invalid-request (:type (ex-data error))))
    (is (= :retry (:field (ex-data error))))
    (is (zero? @attempts))))

(deftest respects-server-retry-delay-and-remaining-budget
  (let [{:keys [platform sleeps]} (deterministic-platform)
        attempts (atom 0)
        timeouts (atom [])
        client (jvm/client
                 {:api-key "test-key"
                  :platform platform
                  :request-fn
                  (fn [request]
                    (swap! timeouts conj (:timeout-ms request))
                    (if (= 1 (swap! attempts inc))
                      {:status 503
                       :headers {"retry-after-ms" "250"}
                       :body "busy"}
                      {:status 200
                       :headers {}
                       :body (response-body
                               {"ok" {:type "noul" :noul 0.9}})}))
                  :timeout-ms 1000
                  :retry {:max-retries 1
                          :backoff-initial-ms 800
                          :backoff-max-ms 800
                          :backoff-jitter 0
                          :budget-ms 1000}})
        result (clojev/system-one
                 client "state" {:ok (clojev/noul "OK?")})]
    (is (= 0.9 (get-in result [:answers :ok :noul])))
    (is (= [250] @sleeps))
    (is (= [1000 750] @timeouts))))

(deftest retries-transport-timeouts-with-configured-backoff
  (let [{:keys [platform sleeps]} (deterministic-platform)
        attempts (atom 0)
        client (jvm/client
                 {:api-key "test-key"
                  :platform platform
                  :request-fn
                  (fn [_]
                    (if (= 1 (swap! attempts inc))
                      (throw
                        (ex-info "timeout" {:type :clojev/timeout}))
                      {:status 200
                       :headers {}
                       :body (response-body
                               {"ok" {:type "noul" :noul 0.9}})}))
                  :retry {:max-retries 1
                          :backoff-initial-ms 100
                          :backoff-max-ms 100
                          :backoff-jitter 0
                          :budget-ms 1000}})
        result (clojev/system-one
                 client "state" {:ok (clojev/noul "OK?")})]
    (is (= 0.9 (get-in result [:answers :ok :noul])))
    (is (= 2 @attempts))
    (is (= [100] @sleeps))))

(deftest retry-budget-prevents-late-attempt
  (let [{:keys [platform sleeps]} (deterministic-platform)
        attempts (atom 0)
        client (jvm/client
                 {:api-key "test-key"
                  :platform platform
                  :request-fn
                  (fn [_]
                    (swap! attempts inc)
                    (throw
                      (ex-info "offline"
                               {:type :clojev/connection-error})))
                  :retry {:max-retries 3
                          :backoff-initial-ms 100
                          :backoff-max-ms 100
                          :backoff-jitter 0
                          :budget-ms 100}})
        error (try
                (clojev/system-one
                  client "state" {:ok (clojev/noul "OK?")})
                (catch clojure.lang.ExceptionInfo error error))]
    (is (= :clojev/connection-error (:type (ex-data error))))
    (is (= 1 @attempts))
    (is (empty? @sleeps))))

(deftest maps-api-statuses-to-specific-errors
  (doseq [[status expected]
          [[400 :clojev/bad-request]
           [401 :clojev/authentication-error]
           [403 :clojev/permission-denied]
           [404 :clojev/not-found]
           [422 :clojev/unprocessable-entity]
           [429 :clojev/rate-limit-error]
           [529 :clojev/internal-server-error]
           [418 :clojev/api-error]]]
    (let [client (jvm/client
                   {:api-key "test-key"
                    :request-fn
                    (fn [_]
                      {:status status
                       :headers {}
                       :body "{\"error\":\"failed\"}"})
                    :retry {:max-retries 0}})
          error (try
                  (clojev/system-one
                    client "state" {:ok (clojev/noul "OK?")})
                  (catch clojure.lang.ExceptionInfo error error))]
      (is (= expected (:type (ex-data error)))
          (str "status " status))
      (is (= status (:status (ex-data error)))))))

(deftest rejects-malformed-success-responses
  (testing "missing answer fields identify the exact field path"
    (let [client (jvm/client
                   {:api-key "test-key"
                    :request-fn
                    (fn [_]
                      {:status 200
                       :headers {}
                       :body
                       (response-body
                         {"route" {:type "choice"
                                   :choice "fast"
                                   :probabilities {"fast" 1.0}}})})})
          error (try
                  (clojev/system-one
                    client
                    "state"
                    {:route (clojev/choice "Route?" {"fast" nil})})
                  (catch clojure.lang.ExceptionInfo error error))]
      (is (= :clojev/response-validation-error
             (:type (ex-data error))))
      (is (= "answers.route.confidence"
             (:field-path (ex-data error))))))

  (testing "probabilities must remain inside the unit interval"
    (let [client (jvm/client
                   {:api-key "test-key"
                    :request-fn
                    (fn [_]
                      {:status 200
                       :headers {}
                       :body
                       (response-body
                         {"route" {:type "choice"
                                   :choice "fast"
                                   :probabilities {"fast" 1.2}
                                   :confidence 0.8}})})})
          error (try
                  (clojev/system-one
                    client
                    "state"
                    {:route (clojev/choice "Route?" {"fast" nil})})
                  (catch clojure.lang.ExceptionInfo error error))]
      (is (= "answers.route.probabilities"
             (:field-path (ex-data error)))))))

(deftest rejects-answer-id-and-type-mismatches
  (let [questions
        {:truth (clojev/noul "True?")
         "route" (clojev/choice "Route?" {"fast" nil})}
        valid-usage {"input_tokens" 1 "output_tokens" 1}
        cases
        [["missing answer"
          {"truth" {"type" "noul" "noul" 1.0}}
          "answers"]
         ["additional answer"
          {"truth" {"type" "noul" "noul" 1.0}
           "route" {"type" "choice"
                    "choice" "fast"
                    "probabilities" {"fast" 1.0}
                    "confidence" 1.0}
           "extra" {"type" "noul" "noul" 0.0}}
          "answers"]
         ["primitive mismatch"
          {"truth" {"type" "choice"
                    "choice" "fast"
                    "probabilities" {"fast" 1.0}
                    "confidence" 1.0}
           "route" {"type" "choice"
                    "choice" "fast"
                    "probabilities" {"fast" 1.0}
                    "confidence" 1.0}}
          "answers.truth.type"]]]
    (doseq [[label answers path] cases]
      (testing label
        (let [error (response-error
                      questions
                      {"model" "jev-versioned"
                       "answers" answers
                       "usage" valid-usage})]
          (is (= :clojev/response-validation-error (:type error)))
          (is (= path (:field-path error)))
          (is (= "req_validation" (:request-id error))))))))

(deftest rejects-inconsistent-choice-distributions
  (let [questions
        {:route (clojev/choice
                  "Route?"
                  {"fast" nil "safe" {"reason" "lower risk"}})}
        cases
        [["missing submitted option"
          {"type" "choice"
           "choice" "fast"
           "probabilities" {"fast" 1.0}
           "confidence" 0.8}
          "answers.route.probabilities"]
         ["additional option"
          {"type" "choice"
           "choice" "fast"
           "probabilities" {"fast" 0.5 "safe" 0.4 "other" 0.1}
           "confidence" 0.8}
          "answers.route.probabilities"]
         ["probability total"
          {"type" "choice"
           "choice" "fast"
           "probabilities" {"fast" 0.6 "safe" 0.3}
           "confidence" 0.8}
          "answers.route.probabilities"]
         ["choice is not highest probability"
          {"type" "choice"
           "choice" "safe"
           "probabilities" {"fast" 0.8 "safe" 0.2}
           "confidence" 0.8}
          "answers.route.choice"]]]
    (doseq [[label answer path] cases]
      (testing label
        (let [error (response-error
                      questions
                      {"model" "jev-versioned"
                       "answers" {"route" answer}
                       "usage" {"input_tokens" 2 "output_tokens" 1}})]
          (is (= :clojev/response-validation-error (:type error)))
          (is (= path (:field-path error))))))))

(deftest rejects-inconsistent-score-distributions
  (let [levels ["low" {"label" "medium"} ["high"]]
        questions {:rating (clojev/score "Rating?" levels)}
        valid-probabilities {"0" 0.25 "1" 0.5 "2" 0.25}
        valid-legend {"0" "low"
                      "1" {"label" "medium"}
                      "2" ["high"]}
        answer
        (fn [overrides]
          (merge {"type" "score"
                  "score" 1.0
                  "legend" valid-legend
                  "probabilities" valid-probabilities
                  "confidence" 0.9}
                 overrides))
        cases
        [["legend keys"
          (answer {"legend" {"0" "low"
                             "1" {"label" "medium"}
                             "3" ["high"]}})
          "answers.rating.legend"]
         ["legend content"
          (answer {"legend" {"0" "low"
                             "1" {"label" "wrong"}
                             "2" ["high"]}})
          "answers.rating.legend"]
         ["probability keys"
          (answer {"probabilities" {"0" 0.25 "1" 0.75}})
          "answers.rating.probabilities"]
         ["probability total"
          (answer {"probabilities" {"0" 0.25 "1" 0.5 "2" 0.2}})
          "answers.rating.probabilities"]
         ["weighted score"
          (answer {"score" 1.5})
          "answers.rating.score"]
         ["score outside level range"
          (answer {"score" 3.0})
          "answers.rating.score"]]]
    (doseq [[label invalid-answer path] cases]
      (testing label
        (let [error (response-error
                      questions
                      {"model" "jev-versioned"
                       "answers" {"rating" invalid-answer}
                       "usage" {"input_tokens" 2 "output_tokens" 1}})]
          (is (= :clojev/response-validation-error (:type error)))
          (is (= path (:field-path error))))))))

(deftest rejects-invalid-model-and-token-usage
  (let [questions {:ok (clojev/noul "OK?")}
        valid-answer {"ok" {"type" "noul" "noul" 1.0}}
        cases
        [["model" 4 {"input_tokens" 1 "output_tokens" 1} "model"]
         ["negative input tokens"
          "jev-versioned"
          {"input_tokens" -1 "output_tokens" 1}
          "usage.input_tokens"]
         ["fractional output tokens"
          "jev-versioned"
          {"input_tokens" 1 "output_tokens" 0.5}
          "usage.output_tokens"]]]
    (doseq [[label model usage path] cases]
      (testing label
        (let [error (response-error
                      questions
                      {"model" model
                       "answers" valid-answer
                       "usage" usage})]
          (is (= :clojev/response-validation-error (:type error)))
          (is (= path (:field-path error))))))))

(deftest accepts-omitted-and-null-token-usage
  (doseq [usage [{} {"input_tokens" nil "output_tokens" nil}]]
    (let [client (jvm/client
                   {:api-key "test-key"
                    :request-fn
                    (fn [_]
                      {:status 200
                       :headers {}
                       :body
                       (json/write-str
                         {"model" "jev-versioned"
                          "answers"
                          {"ok" {"type" "noul" "noul" 1.0}}
                          "usage" usage})})})
          result (clojev/system-one
                   client "state" {:ok (clojev/noul "OK?")})]
      (is (= {:input-tokens nil :output-tokens nil}
             (:usage result))))))

(deftest rejects-invalid-transport-response-maps
  (let [client (jvm/client
                 {:api-key "test-key"
                  :request-fn
                  (fn [_]
                    {:status 200
                     :headers {:content-type "application/json"}
                     :body nil})
                  :retry {:max-retries 0}})
        error (try
                (clojev/system-one
                  client "state" {:ok (clojev/noul "OK?")})
                (catch clojure.lang.ExceptionInfo error error))]
    (is (= :clojev/connection-error (:type (ex-data error))))))

(deftest lists-models-through-jvm-http
  (let [seen (promise)]
    (with-server
      (fn [exchange]
        (deliver seen
                 {:method (.getRequestMethod exchange)
                  :path (.getPath (.getRequestURI exchange))
                  :authorization (.getFirst (.getRequestHeaders exchange)
                                            "Authorization")})
        (respond!
          exchange
          200
          "{\"models\":[{\"name\":\"jev-stable\",\"description\":\"Stable model\",\"release_date\":\"2026-08-01T00:00:00Z\"},{\"name\":\"jev-latest\",\"description\":\"Latest stable model\",\"release_date\":\"2026-09-10T18:38:01Z\"}]}"
          {}))
      (fn [base-url]
        (let [result (clojev/list-models
                       (jvm/client
                         {:api-key "test-key"
                          :base-url base-url}))]
          (is (= {:method "GET"
                  :path "/v1/models"
                  :authorization "Bearer test-key"}
                 @seen))
          (is (= {:models
                  [{:name "jev-stable"
                    :description "Stable model"
                    :release-date "2026-08-01T00:00:00Z"}
                   {:name "jev-latest"
                    :description "Latest stable model"
                    :release-date "2026-09-10T18:38:01Z"}]}
                 result)))))))

(deftest rejects-malformed-model-list-responses
  (let [client (jvm/client
                 {:api-key "test-key"
                  :request-fn
                  (fn [_]
                    {:status 200
                     :headers {}
                     :body
                     "{\"models\":[{\"name\":\"jev-latest\",\"description\":\"Latest\"}]}"})})
        error (try
                (clojev/list-models client)
                (catch clojure.lang.ExceptionInfo error error))]
    (is (= :clojev/response-validation-error
           (:type (ex-data error))))
    (is (= "models.0.release_date"
           (:field-path (ex-data error))))))
