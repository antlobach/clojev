(ns clojev.http.jvm
  "JVM backend for CloJev.

  The default transport uses java.net.http.HttpClient. Pass :transport or
  :request-fn to client to use hato, http-kit, clj-http, or another JVM client."
  (:require [clojure.string :as str]
            [clojev.core :as clojev]
            [clojev.transport :as transport])
  (:import (java.io IOException)
           (java.net URI)
           (java.net.http HttpClient
                          HttpClient$Redirect
                          HttpRequest
                          HttpRequest$BodyPublishers
                          HttpResponse
                          HttpResponse$BodyHandlers
                          HttpTimeoutException)
           (java.time Duration ZonedDateTime)
           (java.time.format DateTimeFormatter DateTimeParseException)
           (java.util.concurrent ThreadLocalRandom)))

(defrecord JvmPlatform []
  transport/Platform
  (-now-ms [_]
    (quot (System/nanoTime) 1000000))
  (-sleep-ms! [_ milliseconds]
    (when (pos? milliseconds)
      (try
        (Thread/sleep (long milliseconds))
        (catch InterruptedException error
          (.interrupt (Thread/currentThread))
          (throw (ex-info "Interrupted while waiting to retry"
                          {:type :clojev/connection-error}
                          error))))))
  (-random-double [_]
    (.nextDouble (ThreadLocalRandom/current)))
  (-getenv [_ name]
    (System/getenv name)))

(defn platform
  "Returns the standard JVM platform implementation."
  []
  (->JvmPlatform))

(defn- response-headers [^HttpResponse response]
  (into {}
        (map (fn [[name values]]
               [(str/lower-case name) (str (first values))]))
        (.map (.headers response))))

(defn- parse-number [value]
  (try
    (Double/parseDouble value)
    (catch NumberFormatException _ nil)))

(defn- parse-retry-after-ms [headers]
  (or
    (some-> (get headers "retry-after-ms")
            parse-number
            long
            (max 0))
    (when-let [value (get headers "retry-after")]
      (or (some-> (parse-number value)
                  (* 1000.0)
                  long
                  (max 0))
          (try
            (max 0
                 (- (.toEpochMilli
                      (.toInstant
                        (ZonedDateTime/parse
                          value DateTimeFormatter/RFC_1123_DATE_TIME)))
                    (System/currentTimeMillis)))
            (catch DateTimeParseException _ nil))))))

(defrecord JdkTransport [^HttpClient http-client]
  transport/Transport
  (-send! [_ {:keys [method url headers body timeout-ms]}]
    (try
      (let [builder (HttpRequest/newBuilder (URI/create url))]
        (doseq [[name value] headers]
          (.setHeader builder name value))
        (.timeout builder (Duration/ofMillis timeout-ms))
        (case method
          :get (.GET builder)
          :post (.POST builder (HttpRequest$BodyPublishers/ofString body))
          (throw (ex-info "JDK transport supports only :get and :post"
                          {:type :clojev/invalid-request :field :method})))
        (let [^HttpResponse response (.send ^HttpClient http-client
                                            (.build builder)
                                            (HttpResponse$BodyHandlers/ofString))
              headers (response-headers response)
              retry-after-ms (parse-retry-after-ms headers)]
          (cond-> {:status (.statusCode response)
                   :headers headers
                   :body (.body response)}
            retry-after-ms
            (assoc :retry-after-ms retry-after-ms))))
      (catch HttpTimeoutException error
        (throw (ex-info "HTTP request timed out"
                        {:type :clojev/timeout :timeout-ms timeout-ms}
                        error)))
      (catch IOException error
        (throw (ex-info "HTTP connection failed"
                        {:type :clojev/connection-error}
                        error)))
      (catch InterruptedException error
        (.interrupt (Thread/currentThread))
        (throw (ex-info "HTTP request was interrupted"
                        {:type :clojev/connection-error}
                        error))))))

(defn jdk-transport
  "Creates the built-in java.net.http transport.

  Options are :http-client for an existing HttpClient and
  :connect-timeout-ms for a newly-created client's connection timeout."
  ([] (jdk-transport {}))
  ([{:keys [http-client connect-timeout-ms]
     :or {connect-timeout-ms clojev/default-timeout-ms}}]
   (->JdkTransport
     (or http-client
         (-> (HttpClient/newBuilder)
             (.connectTimeout (Duration/ofMillis connect-timeout-ms))
             (.followRedirects HttpClient$Redirect/NEVER)
             .build)))))

(defn client
  "Creates a CloJev client for the JVM.

  Uses java.net.http by default. To swap in any other HTTP client, pass either
  a clojev.transport/Transport as :transport or a normalized request function as
  :request-fn. The function receives {:method :url :headers :body :timeout-ms}
  and returns {:status :headers :body}, with optional :retry-after-ms."
  ([] (client {}))
  ([opts]
   (when (and (:transport opts) (:request-fn opts))
     (throw (ex-info "Pass either :transport or :request-fn, not both"
                     {:type :clojev/invalid-request :field :transport})))
   (let [http-transport (or (:transport opts)
                            (some-> (:request-fn opts)
                                    transport/function-transport)
                            (jdk-transport opts))
         runtime (or (:platform opts) (platform))]
     (clojev/client
       (-> opts
           (dissoc :request-fn :http-client :connect-timeout-ms)
           (assoc :transport http-transport :platform runtime))))))
