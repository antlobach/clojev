(ns clojev.core
  "Portable synchronous core for TypeSafe's Jev System One API.

  HTTP, environment access, clocks, sleeping, and randomness are supplied by a
  backend through clojev.transport."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojev.transport :as transport]))

(def default-base-url
  "The default TypeSafe API root."
  "https://api.typesafe.ai")

(def default-model
  "The default Jev model alias."
  "jev-latest")

(def default-timeout-ms
  "The default timeout for each HTTP attempt, in milliseconds."
  10000)

(def ^:private sdk-version "0.1.0")

(def ^:private default-retry-options
  {:max-retries 2
   :backoff-initial-ms 500
   :backoff-max-ms 5000
   :backoff-jitter 0.25
   :statuses (into #{408 429} (range 500 600))
   :respect-retry-after? true
   :budget-ms 30000})

(defn- fail! [message data]
  (throw (ex-info message (merge {:type :clojev/invalid-request} data))))

(defn- nonblank-string? [value]
  (and (string? value) (not (str/blank? value))))

(defn- positive-integer? [value]
  (and (integer? value) (pos? value)))

(defn retry-policy
  "Returns a validated retry policy.

  Overrides may contain :max-retries, :backoff-initial-ms,
  :backoff-max-ms, :backoff-jitter, :statuses,
  :respect-retry-after?, and :budget-ms. Set :max-retries to 0 to
  disable retries or :budget-ms to nil to remove the total retry budget."
  ([] default-retry-options)
  ([overrides]
   (when-not (or (nil? overrides) (map? overrides))
     (fail! "retry policy overrides must be a map" {:field :retry}))
   (let [policy (merge default-retry-options overrides)]
     (when-not (and (integer? (:max-retries policy))
                    (not (neg? (:max-retries policy))))
       (fail! ":max-retries must be a non-negative integer"
              {:field :max-retries}))
     (doseq [field [:backoff-initial-ms :backoff-max-ms]]
       (when-not (and (number? (get policy field))
                      (not (neg? (get policy field))))
         (fail! (str field " must be non-negative") {:field field})))
     (when-not (and (number? (:backoff-jitter policy))
                    (<= 0 (:backoff-jitter policy) 1))
       (fail! ":backoff-jitter must be between 0 and 1"
              {:field :backoff-jitter}))
     (when-not (and (set? (:statuses policy))
                    (every? integer? (:statuses policy)))
       (fail! ":statuses must be a set of integers" {:field :statuses}))
     (when-not (boolean? (:respect-retry-after? policy))
       (fail! ":respect-retry-after? must be boolean"
              {:field :respect-retry-after?}))
     (when-not (or (nil? (:budget-ms policy))
                   (positive-integer? (:budget-ms policy)))
       (fail! ":budget-ms must be nil or a positive integer"
              {:field :budget-ms}))
     policy)))

(defn- trim-trailing-slashes [value]
  (loop [value value]
    (if (and (seq value) (= \/ (last value)))
      (recur (subs value 0 (dec (count value))))
      value)))

(defn- env-value [platform name]
  (some-> (transport/getenv platform name) str/trim not-empty))

(defn client
  "Creates a portable CloJev client around a transport and platform.

  Required options:
  - :transport — an implementation of clojev.transport/Transport.
  - :platform — an implementation of clojev.transport/Platform.

  Other options:
  - :api-key — defaults to TYPESAFE_API_KEY and is required.
  - :model — defaults to TYPESAFE_DEFAULT_MODEL or jev-latest.
  - :base-url — defaults to TYPESAFE_BASE_URL or the public API root.
  - :timeout-ms — per-attempt timeout; defaults to 10000.
  - :retry — retry-policy overrides.
  - :headers — additional string HTTP headers.

  Runtime backends normally wrap this function and provide the two required
  implementations automatically."
  [opts]
  (when-not (map? opts)
    (fail! "client options must be a map" {:field :client}))
  (let [http-transport (:transport opts)
        platform (:platform opts)]
    (when-not (satisfies? transport/Transport http-transport)
      (fail! ":transport must implement clojev.transport/Transport"
             {:field :transport}))
    (when-not (satisfies? transport/Platform platform)
      (fail! ":platform must implement clojev.transport/Platform"
             {:field :platform}))
    (let [api-key (if (contains? opts :api-key)
                    (:api-key opts)
                    (env-value platform "TYPESAFE_API_KEY"))
          model (if (contains? opts :model)
                  (:model opts)
                  (or (env-value platform "TYPESAFE_DEFAULT_MODEL")
                      default-model))
          base-url (if (contains? opts :base-url)
                     (:base-url opts)
                     (or (env-value platform "TYPESAFE_BASE_URL")
                         default-base-url))
          timeout-ms (get opts :timeout-ms default-timeout-ms)
          headers (get opts :headers {})]
      (when-not (nonblank-string? api-key)
        (fail! "API key is required; pass :api-key or set TYPESAFE_API_KEY"
               {:field :api-key}))
      (when-not (nonblank-string? model)
        (fail! ":model must be a non-blank string" {:field :model}))
      (when-not (nonblank-string? base-url)
        (fail! ":base-url must be a non-blank string" {:field :base-url}))
      (when-not (positive-integer? timeout-ms)
        (fail! ":timeout-ms must be a positive integer"
               {:field :timeout-ms}))
      (when-not (map? headers)
        (fail! ":headers must be a map" {:field :headers}))
      {::client true
       :transport http-transport
       :platform platform
       :api-key api-key
       :model model
       :base-url (trim-trailing-slashes base-url)
       :timeout-ms timeout-ms
       :headers headers
       :retry (retry-policy (:retry opts))})))

(defn- required [value field]
  (when (nil? value)
    (fail! (str field " is required") {:field field}))
  value)

(defn noul
  "Builds a yes/no question. Criteria may describe :true and :false outcomes."
  ([instructions] (noul instructions nil))
  ([instructions criteria]
   (when-not (or (nil? criteria) (map? criteria))
     (fail! "noul criteria must be a map" {:field :criteria}))
   (cond-> {:type "noul"
            :instructions (required instructions :instructions)}
     (some? criteria) (assoc :criteria criteria))))

(defn choice
  "Builds a choice question with 1 to 255 named options."
  [instructions criteria]
  (when-not (and (map? criteria) (<= 1 (count criteria) 255))
    (fail! "choice criteria must contain 1 to 255 options"
           {:field :criteria}))
  {:type "choice"
   :instructions (required instructions :instructions)
   :criteria criteria})

(defn score
  "Builds a score question with 2 to 10 ordered levels."
  [instructions criteria]
  (when-not (and (sequential? criteria) (<= 2 (count criteria) 10))
    (fail! "score criteria must contain 2 to 10 levels"
           {:field :criteria}))
  {:type "score"
   :instructions (required instructions :instructions)
   :criteria (vec criteria)})

(defn- map-field [m field]
  (if (contains? m field)
    (get m field)
    (get m (name field))))

(defn- contains-field? [m field]
  (or (contains? m field) (contains? m (name field))))

(defn- question-id->wire [id]
  (let [wire-id (cond
                  (string? id) id
                  (keyword? id) (subs (str id) 1)
                  :else (fail! "question ids must be strings or keywords"
                               {:field :questions :question-id id}))]
    (when (str/blank? wire-id)
      (fail! "question ids must not be blank"
             {:field :questions :question-id id}))
    wire-id))

(defn- validate-question [id question]
  (when-not (map? question)
    (fail! "each question must be a map"
           {:field :questions :question-id id}))
  (let [type (map-field question :type)
        criteria (map-field question :criteria)]
    (when-not (contains? {"noul" true "choice" true "score" true} type)
      (fail! "question type must be noul, choice, or score"
             {:field :type :question-id id}))
    (when-not (and (contains-field? question :instructions)
                   (some? (map-field question :instructions)))
      (fail! "question instructions are required"
             {:field :instructions :question-id id}))
    (case type
      "noul"
      (when (and (contains-field? question :criteria)
                 (not (map? criteria)))
        (fail! "noul criteria must be a map"
               {:field :criteria :question-id id}))

      "choice"
      (when-not (and (map? criteria) (<= 1 (count criteria) 255))
        (fail! "choice criteria must contain 1 to 255 options"
               {:field :criteria :question-id id}))

      "score"
      (when-not (and (sequential? criteria) (<= 2 (count criteria) 10))
        (fail! "score criteria must contain 2 to 10 levels"
               {:field :criteria :question-id id})))
    type))

(defn- prepare-questions [questions]
  (when-not (and (map? questions) (seq questions))
    (fail! ":questions must be a non-empty map" {:field :questions}))
  (reduce-kv
    (fn [{:keys [wire id-by-wire types] :as prepared} id question]
      (let [wire-id (question-id->wire id)]
        (when (contains? id-by-wire wire-id)
          (fail! "question ids must be unique after JSON encoding"
                 {:field :questions :question-id id}))
        (assoc prepared
               :wire (assoc wire wire-id question)
               :id-by-wire (assoc id-by-wire wire-id id)
               :types (assoc types wire-id (validate-question id question)))))
    {:wire {} :id-by-wire {} :types {}}
    questions))

(defn- in-unit-interval? [value]
  (and (number? value) (<= 0 value 1)))

(defn- probability-map? [value]
  (and (map? value)
       (seq value)
       (every? string? (keys value))
       (every? in-unit-interval? (vals value))))

(defn- non-negative-integer? [value]
  (and (integer? value) (not (neg? value))))

(defn- response-failure! [status body headers endpoint field-path]
  (throw (ex-info (str "Invalid TypeSafe response at " field-path)
                  {:type :clojev/response-validation-error
                   :status status
                   :body body
                   :headers headers
                   :endpoint endpoint
                   :field-path field-path
                   :request-id (get headers "x-typesafe-request-id")})))

(defn- required-response-field
  [m key pred status body headers endpoint field-path]
  (let [value (get m key ::missing)]
    (when (or (= ::missing value) (not (pred value)))
      (response-failure! status body headers endpoint field-path))
    value))

(defn- normalize-answer
  [answer expected-type status body headers endpoint path]
  (when-not (map? answer)
    (response-failure! status body headers endpoint path))
  (let [type (required-response-field
               answer "type" string?
               status body headers endpoint (str path ".type"))]
    (when (not= expected-type type)
      (response-failure! status body headers endpoint (str path ".type")))
    (case type
      "noul"
      {:type :noul
       :noul (required-response-field
               answer "noul" in-unit-interval?
               status body headers endpoint (str path ".noul"))}

      "choice"
      (let [choice (required-response-field
                     answer "choice" string?
                     status body headers endpoint (str path ".choice"))
            probabilities (required-response-field
                            answer "probabilities" probability-map?
                            status body headers endpoint
                            (str path ".probabilities"))]
        (when-not (contains? probabilities choice)
          (response-failure! status body headers endpoint
                             (str path ".choice")))
        {:type :choice
         :choice choice
         :probabilities probabilities
         :confidence (required-response-field
                       answer "confidence" in-unit-interval?
                       status body headers endpoint (str path ".confidence"))})

      "score"
      {:type :score
       :score (required-response-field
                answer "score" number?
                status body headers endpoint (str path ".score"))
       :legend (required-response-field
                 answer "legend" map?
                 status body headers endpoint (str path ".legend"))
       :probabilities (required-response-field
                        answer "probabilities" probability-map?
                        status body headers endpoint
                        (str path ".probabilities"))
       :confidence (required-response-field
                     answer "confidence" in-unit-interval?
                     status body headers endpoint (str path ".confidence"))})))

(defn- normalize-response [raw prepared status body headers endpoint]
  (when-not (map? raw)
    (response-failure! status body headers endpoint "$"))
  (let [model (required-response-field
                raw "model" string? status body headers endpoint "model")
        answers (required-response-field
                  raw "answers" map? status body headers endpoint "answers")
        usage (required-response-field
                raw "usage" map? status body headers endpoint "usage")
        expected-ids (set (keys (:types prepared)))]
    (when-not (= expected-ids (set (keys answers)))
      (response-failure! status body headers endpoint "answers"))
    {:model model
     :answers (reduce-kv
                (fn [result wire-id expected-type]
                  (assoc result
                         (get (:id-by-wire prepared) wire-id)
                         (normalize-answer
                           (get answers wire-id)
                           expected-type
                           status
                           body
                           headers
                           endpoint
                           (str "answers." wire-id))))
                {}
                (:types prepared))
     :usage {:input-tokens (required-response-field
                             usage "input_tokens" non-negative-integer?
                             status body headers endpoint
                             "usage.input_tokens")
             :output-tokens (required-response-field
                              usage "output_tokens" non-negative-integer?
                              status body headers endpoint
                              "usage.output_tokens")}}))

(defn- parse-decimal [value]
  (try
    (let [parsed (edn/read-string value)]
      (when (number? parsed) (double parsed)))
    (catch Exception _ nil)))

(defn- numeric-retry-after-ms [headers]
  (or
    (some-> (get headers "retry-after-ms")
            parse-decimal
            long
            (max 0))
    (some-> (get headers "retry-after")
            parse-decimal
            (* 1000.0)
            long
            (max 0))))

(defn- power-of-two [exponent]
  (loop [remaining exponent
         value 1.0]
    (if (zero? remaining)
      value
      (recur (dec remaining) (* value 2.0)))))

(defn- exponential-delay-ms [platform policy attempt]
  (let [base (min (double (:backoff-max-ms policy))
                  (* (double (:backoff-initial-ms policy))
                     (power-of-two attempt)))
        jitter (* base
                  (double (:backoff-jitter policy))
                  (transport/random-double platform))]
    (long (max 0 (- base jitter)))))

(defn- retry-delay-ms [platform policy attempt response]
  (or (when (:respect-retry-after? policy)
        (or (:retry-after-ms response)
            (numeric-retry-after-ms (:headers response))))
      (exponential-delay-ms platform policy attempt)))

(defn- elapsed-ms [platform started-ms]
  (- (transport/now-ms platform) started-ms))

(defn- retry-allowed? [platform policy attempt started-ms delay-ms]
  (and (< attempt (:max-retries policy))
       (or (nil? (:budget-ms policy))
           (< (+ (elapsed-ms platform started-ms) delay-ms)
              (:budget-ms policy)))))

(defn- parse-error-body [body]
  (when-not (str/blank? body)
    (try
      (json/read-str body)
      (catch Exception _ body))))

(defn- api-error-type [status]
  (cond
    (= status 400) :clojev/bad-request
    (= status 401) :clojev/authentication-error
    (= status 403) :clojev/permission-denied
    (= status 404) :clojev/not-found
    (= status 422) :clojev/unprocessable-entity
    (= status 429) :clojev/rate-limit-error
    (<= 500 status 599) :clojev/internal-server-error
    :else :clojev/api-error))

(defn- api-error! [status body headers endpoint retry-after-ms]
  (throw (ex-info (str "TypeSafe API request failed with status " status)
                  {:type (api-error-type status)
                   :status status
                   :body (parse-error-body body)
                   :headers headers
                   :endpoint endpoint
                   :request-id (get headers "x-typesafe-request-id")
                   :retry-after-ms retry-after-ms})))

(defn- parse-success [status body headers endpoint prepared]
  (let [raw (try
              (json/read-str body)
              (catch Exception _
                (response-failure! status body headers endpoint "$")))]
    (normalize-response raw prepared status body headers endpoint)))

(defn- operation-timeout-ms [platform timeout-ms policy started-ms]
  (if-let [budget-ms (:budget-ms policy)]
    (max 1 (min timeout-ms (- budget-ms (elapsed-ms platform started-ms))))
    timeout-ms))

(defn- connection-error! [kind cause endpoint timeout-ms]
  (throw (ex-info (if (= kind :clojev/timeout)
                    "TypeSafe API request timed out"
                    "Could not connect to the TypeSafe API")
                  {:type kind
                   :endpoint endpoint
                   :timeout-ms timeout-ms}
                  cause)))

(defn- validate-transport-response [response endpoint]
  (when-not (and (map? response)
                 (integer? (:status response))
                 (map? (:headers response))
                 (every? string? (keys (:headers response)))
                 (every? string? (vals (:headers response)))
                 (string? (:body response)))
    (connection-error!
      :clojev/connection-error
      (ex-info "Transport returned an invalid response"
               {:type :clojev/invalid-transport-response
                :response response})
      endpoint
      nil))
  response)

(defn- execute-request
  [client endpoint body prepared timeout-ms headers policy]
  (let [platform (:platform client)
        started-ms (transport/now-ms platform)]
    (loop [attempt 0]
      (let [current-timeout (operation-timeout-ms
                              platform timeout-ms policy started-ms)
            request {:method :post
                     :url endpoint
                     :headers headers
                     :body body
                     :timeout-ms current-timeout}
            outcome (try
                      {:response (validate-transport-response
                                   (transport/send! (:transport client) request)
                                   endpoint)}
                      (catch Exception error
                        {:error error
                         :kind (if (= :clojev/timeout (:type (ex-data error)))
                                 :clojev/timeout
                                 :clojev/connection-error)}))]
        (if-let [response (:response outcome)]
          (let [{:keys [status body headers]} response
                delay-ms (retry-delay-ms platform policy attempt response)]
            (if (and (contains? (:statuses policy) status)
                     (retry-allowed?
                       platform policy attempt started-ms delay-ms))
              (do
                (transport/sleep-ms! platform delay-ms)
                (recur (inc attempt)))
              (if (<= 200 status 299)
                (parse-success status body headers endpoint prepared)
                (api-error!
                  status body headers endpoint
                  (or (:retry-after-ms response)
                      (numeric-retry-after-ms headers))))))
          (let [{:keys [error kind]} outcome
                delay-ms (retry-delay-ms platform policy attempt {})]
            (if (retry-allowed?
                  platform policy attempt started-ms delay-ms)
              (do
                (transport/sleep-ms! platform delay-ms)
                (recur (inc attempt)))
              (connection-error! kind error endpoint timeout-ms))))))))

(def ^:private protected-header-names
  #{"authorization" "accept" "content-type" "user-agent"})

(defn- protected-header? [name]
  (contains? protected-header-names (str/lower-case name)))

(defn system-one
  "Evaluates state against named questions and returns typed answer maps.

  Question ids may be strings or keywords and are preserved in :answers.
  Per-call options are :model, :timeout-ms, :retry, and :extra-headers.
  Failures are ExceptionInfo values whose ex-data contains a :type in the
  :clojev namespace plus request metadata when available."
  ([client state questions]
   (system-one client state questions {}))
  ([client state questions opts]
   (when-not (map? opts)
     (fail! "request options must be a map" {:field :options}))
   (when-not (::client client)
     (fail! "client must be created by clojev.core/client" {:field :client}))
   (when-not (or (string? state) (map? state) (sequential? state))
     (fail! ":state must be a string, map, or sequential collection"
            {:field :state}))
   (let [prepared (prepare-questions questions)
         model (get opts :model (:model client))
         timeout-ms (get opts :timeout-ms (:timeout-ms client))
         extra-headers (get opts :extra-headers {})
         policy (retry-policy (merge (:retry client) (:retry opts)))
         endpoint (str (:base-url client) "/v1/systemone")]
     (when-not (nonblank-string? model)
       (fail! ":model must be a non-blank string" {:field :model}))
     (when-not (positive-integer? timeout-ms)
       (fail! ":timeout-ms must be a positive integer"
              {:field :timeout-ms}))
     (when-not (map? extra-headers)
       (fail! ":extra-headers must be a map" {:field :extra-headers}))
     (let [body (try
                  (json/write-str
                    {:state state
                     :model model
                     :questions (:wire prepared)})
                  (catch Exception error
                    (throw (ex-info "Request is not JSON-encodable"
                                    {:type :clojev/invalid-request
                                     :field :request}
                                    error))))
           custom-headers (merge (:headers client) extra-headers)
           _ (when-not (every? (fn [[name value]]
                                 (and (string? name) (string? value)))
                               custom-headers)
               (fail! "header names and values must be strings"
                      {:field :headers}))
           headers (merge
                     (into {}
                           (remove (fn [[name _]] (protected-header? name)))
                           custom-headers)
                     {"Authorization" (str "Bearer " (:api-key client))
                      "Accept" "application/json"
                      "Content-Type" "application/json"
                      "User-Agent" (str "clojev/" sdk-version)})]
       (execute-request
         client endpoint body prepared timeout-ms headers policy)))))
