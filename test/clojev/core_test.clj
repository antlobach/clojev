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
      "/v1/systemone"
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

(deftest sends-system-one-request-and-decodes-all-answer-types
  (let [seen (promise)]
    (with-server
      (fn [exchange]
        (deliver seen
                 {:method (.getRequestMethod exchange)
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
          (is (= "Bearer test-key" (:authorization request)))
          (is (= "jev-latest" (get (:body request) "model")))
          (is (= {:type :noul :noul 0.91}
                 (get-in result [:answers :urgent])))
          (is (= "billing" (get-in result [:answers "team" :choice])))
          (is (= 1.2 (get-in result [:answers :tone :score])))
          (is (= {:input-tokens 21 :output-tokens 7}
                 (:usage result))))))))

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

(deftest preserves-structured-question-data
  (let [instructions {:potential-duplicate
                      {:name "John Smith"
                       :location "Oakland"}
                      :question
                      "Is the resume for the same person as `potential_duplicate`?"}
        criteria {:true {:meaning "Same person"}
                  :false ["Different person"]}]
    (is (= {:type "noul"
            :instructions instructions
            :criteria criteria}
           (clojev/noul instructions criteria)))))

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
