(ns clojev.fuzz-test
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojev.core :as clojev]
            [clojev.http.jvm :as jvm]
            [clojev.transport :as transport]))

(def ^:private trial-count 1000)

(defn- check-property! [seed property]
  (let [result (tc/quick-check trial-count property :seed seed)]
    (is (:pass? result)
        (pr-str (select-keys result
                             [:seed :num-tests :fail :shrunk :result])))))

(defn- capture [f]
  (try
    {:value (f)}
    (catch clojure.lang.ExceptionInfo error
      {:error (ex-data error)})))

(defn- criteria-map [n]
  (into {}
        (map (fn [index]
               [(str "option-" index) (str "Description " index)]))
        (range n)))

(def ^:private id-text-gen
  (gen/such-that seq gen/string-alphanumeric 100))

(def ^:private question-id-gen
  (gen/one-of [id-text-gen (gen/fmap keyword id-text-gen)]))

(def ^:private state-gen
  (gen/one-of
    [gen/string-alphanumeric
     (gen/vector (gen/choose -1000 1000) 0 10)
     (gen/map id-text-gen (gen/choose -1000 1000) {:max-elements 8})]))

(def ^:private protected-name-gen
  (gen/elements ["authorization" "accept" "content-type" "user-agent"]))

(defn- case-variant-gen [name]
  (gen/fmap
    (fn [upper?]
      (apply str
             (map (fn [character uppercase?]
                    (if uppercase?
                      (Character/toUpperCase ^char character)
                      (Character/toLowerCase ^char character)))
                  name
                  upper?)))
    (gen/vector gen/boolean (count name))))

(def ^:private protected-header-gen
  (gen/bind protected-name-gen case-variant-gen))

(def ^:private invalid-probability-gen
  (gen/one-of
    [(gen/fmap #(/ % 100.0) (gen/choose -1000 -1))
     (gen/fmap #(/ % 100.0) (gen/choose 101 1000))
     (gen/elements [nil "0.5" true false [] {}])]))

(defn- fuzz-platform [sleeps]
  (reify transport/Platform
    (-now-ms [_] 0)
    (-sleep-ms! [_ milliseconds]
      (swap! sleeps conj milliseconds))
    (-random-double [_] 0.5)
    (-getenv [_ _] nil)))

(deftest fuzzes-question-constructor-boundaries
  (testing "choice accepts exactly 1 to 255 options"
    (check-property!
      1101
      (prop/for-all [n (gen/choose 0 270)]
        (let [criteria (criteria-map n)
              {:keys [error]} (capture #(clojev/choice "Choose" criteria))]
          (if (<= 1 n 255)
            (nil? error)
            (and (= :clojev/invalid-request (:type error))
                 (= :criteria (:field error))))))))
  (testing "score accepts exactly 2 to 10 levels"
    (check-property!
      1102
      (prop/for-all [n (gen/choose 0 15)]
        (let [criteria (mapv #(str "level-" %) (range n))
              {:keys [error]} (capture #(clojev/score "Score" criteria))]
          (if (<= 2 n 10)
            (nil? error)
            (and (= :clojev/invalid-request (:type error))
                 (= :criteria (:field error)))))))))

(deftest fuzzes-request-and-response-round-trips
  (check-property!
    1201
    (prop/for-all [id question-id-gen
                   state state-gen
                   probability-thousandths (gen/choose 0 1000)
                   input-tokens (gen/choose 0 10000)
                   output-tokens (gen/choose 0 10000)]
      (let [wire-id (if (keyword? id) (subs (str id) 1) id)
            probability (/ probability-thousandths 1000.0)
            seen (atom nil)
            client
            (jvm/client
              {:api-key "test-key"
               :request-fn
               (fn [request]
                 (reset! seen (json/read-str (:body request)))
                 {:status 200
                  :headers {}
                  :body
                  (json/write-str
                    {:model "jev-fuzz"
                     :answers
                     {wire-id {:type "noul" :noul probability}}
                     :usage {:input_tokens input-tokens
                             :output_tokens output-tokens}})})})
            result
            (clojev/system-one
              client state {id (clojev/noul "Fuzz?")})]
        (and (= state (get @seen "state"))
             (= {:type :noul :noul probability}
                (get-in result [:answers id]))
             (= {:input-tokens input-tokens
                 :output-tokens output-tokens}
                (:usage result)))))))

(deftest fuzzes-protected-header-casing
  (check-property!
    1301
    (prop/for-all [header protected-header-gen
                   at-call? gen/boolean]
      (let [seen (atom nil)
            injected {header "attacker-controlled"}
            client
            (jvm/client
              {:api-key "test-key"
               :headers (if at-call? {} injected)
               :request-fn
               (fn [request]
                 (reset! seen request)
                 {:status 200
                  :headers {}
                  :body
                  (json/write-str
                    {:model "jev-fuzz"
                     :answers {"ok" {:type "noul" :noul 1.0}}
                     :usage {:input_tokens 1 :output_tokens 1}})})})
            _ (clojev/system-one
                client
                "state"
                {:ok (clojev/noul "OK?")}
                (if at-call? {:extra-headers injected} {}))
            normalized
            (into {}
                  (map (fn [[name value]]
                         [(str/lower-case name) value]))
                  (:headers @seen))]
        (and (= "Bearer test-key" (get normalized "authorization"))
             (= "application/json" (get normalized "accept"))
             (= "application/json" (get normalized "content-type"))
             (str/starts-with? (get normalized "user-agent") "clojev/")
             (not-any? #(= "attacker-controlled" %)
                       (vals normalized)))))))

(deftest fuzzes-retry-attempt-bounds
  (check-property!
    1401
    (prop/for-all [max-retries (gen/choose 0 8)]
      (let [attempts (atom 0)
            sleeps (atom [])
            client
            (jvm/client
              {:api-key "test-key"
               :platform (fuzz-platform sleeps)
               :request-fn
               (fn [_]
                 (swap! attempts inc)
                 {:status 503
                  :headers {}
                  :body "{\"error\":\"busy\"}"})
               :retry {:max-retries max-retries
                       :backoff-initial-ms 0
                       :backoff-max-ms 0
                       :backoff-jitter 0
                       :budget-ms nil}})
            {:keys [error]}
            (capture
              #(clojev/system-one
                 client "state" {:ok (clojev/noul "OK?")}))]
        (and (= :clojev/internal-server-error (:type error))
             (= (inc max-retries) @attempts)
             (= max-retries (count @sleeps)))))))

(deftest fuzzes-invalid-response-probabilities
  (check-property!
    1501
    (prop/for-all [invalid invalid-probability-gen]
      (let [client
            (jvm/client
              {:api-key "test-key"
               :request-fn
               (fn [_]
                 {:status 200
                  :headers {}
                  :body
                  (json/write-str
                    {:model "jev-fuzz"
                     :answers
                     {"route"
                      {:type "choice"
                       :choice "yes"
                       :probabilities {"yes" invalid}
                       :confidence 0.5}}
                     :usage {:input_tokens 1 :output_tokens 1}})})})
            {:keys [error]}
            (capture
              #(clojev/system-one
                 client
                 "state"
                 {:route (clojev/choice "Route?" {"yes" nil})}))]
        (and (= :clojev/response-validation-error (:type error))
             (= "answers.route.probabilities" (:field-path error)))))))
