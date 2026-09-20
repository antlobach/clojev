(ns clojev.transport
  "Portable contracts between CloJev's core and runtime-specific HTTP code.")

(defprotocol Transport
  "A synchronous HTTP transport.

  Requests have :method, :url, :headers, and :timeout-ms. POST requests also
  have a string :body.
  Responses must have integer :status, string-to-string :headers, and a string
  :body. A transport may include normalized :retry-after-ms. Connection and
  timeout failures should throw ExceptionInfo with :type
  :clojev/connection-error or :clojev/timeout."
  (-send! [transport request]))

(defprotocol Platform
  "The small runtime surface needed by portable retry and configuration code."
  (-now-ms [platform])
  (-sleep-ms! [platform milliseconds])
  (-random-double [platform])
  (-getenv [platform name]))

(defrecord FunctionTransport [request-fn]
  Transport
  (-send! [_ request]
    (request-fn request)))

(defn function-transport
  "Adapts a normalized request function to Transport.

  This is the integration point for clj-http, http-kit, hato, custom clients,
  test doubles, and non-JVM clients. The function receives and returns the maps
  described by Transport."
  [request-fn]
  (when-not (ifn? request-fn)
    (throw (ex-info "request-fn must be callable"
                    {:type :clojev/invalid-request :field :transport})))
  (->FunctionTransport request-fn))

(defn send!
  "Sends a normalized request through transport."
  [transport request]
  (-send! transport request))

(defn now-ms [platform]
  (-now-ms platform))

(defn sleep-ms! [platform milliseconds]
  (-sleep-ms! platform milliseconds))

(defn random-double [platform]
  (-random-double platform))

(defn getenv [platform name]
  (-getenv platform name))
