# CloJev

CloJev is an independent Clojure SDK for the TypeSafe System One API.

> **Unofficial project:** TypeSafe does not develop, sponsor, endorse, or support CloJev. This project uses the names TypeSafe, Jev, and System One only to identify the compatible API.

CloJev sends state and typed questions to `POST /v1/systemone`. It returns Clojure maps with validated answers.

CloJev includes these features:

- Noul, Choice, and Score question constructors
- Keyword or string question identifiers
- Response validation
- Exponential retry delays with jitter
- `Retry-After` support
- Structured API and transport errors
- A default JVM backend based on `java.net.http.HttpClient`
- A native Jolt backend based on `jolt-lang/http-client`
- A portable `.cljc` core with replaceable transport and platform protocols

See the [API reference](docs/api.md) for all public functions and data shapes.

## Requirements

### JVM

Use Clojure 1.12 or later on Java 11 or later.

### Jolt

Use Jolt 0.8.9 or later. HTTPS requests also require OpenSSL.

## Install from GitHub

Add CloJev to `deps.edn`:

```clojure
{:deps
 {io.github.antlobach/clojev
  {:git/url "https://github.com/antlobach/clojev.git"
   :git/branch "main"}}}
```

A Git commit SHA gives a reproducible dependency. Replace `:git/branch` with `:git/sha` when you select a release commit.

## Configure the API key

Set the API key in your process environment:

```sh
export TYPESAFE_API_KEY="your-api-key"
```

Do not store the key in source control.

You can also pass the key when you create a client:

```clojure
(http/client {:api-key "your-api-key"})
```

The environment variable is safer for deployed applications.

CloJev reads these environment variables:

| Variable | Purpose |
|---|---|
| `TYPESAFE_API_KEY` | TypeSafe API key |
| `TYPESAFE_DEFAULT_MODEL` | Default model name |
| `TYPESAFE_BASE_URL` | TypeSafe API root |

Explicit client options take precedence over environment variables.

## JVM quick start

```clojure
(require '[clojev.core :as clojev]
         '[clojev.http.jvm :as http])

(def client (http/client))

(def result
  (clojev/system-one
    client
    "Help! My payouts have failed for three days."
    {:urgent
     (clojev/noul "Does this message convey urgency?")

     :department
     (clojev/choice
       "Which team must handle this request?"
       {"billing" "Payments, invoices, and refunds"
        "technical" "Bugs, outages, and integrations"
        "sales" "Pricing, upgrades, and new accounts"})

     :frustration
     (clojev/score
       "How frustrated is the customer?"
       ["Calm" "Frustrated" "Very angry"])}))
```

The result has this shape:

```clojure
{:model "jev-1.13.0"
 :answers
 {:urgent {:type :noul
           :noul 0.95}
  :department {:type :choice
               :choice "billing"
               :probabilities {"billing" 0.88
                               "technical" 0.12
                               "sales" 0.0}
               :confidence 0.81}
  :frustration {:type :score
                :score 1.05
                :legend {"0" "Calm"
                         "1" "Frustrated"
                         "2" "Very angry"}
                :probabilities {"0" 0.0
                                "1" 0.95
                                "2" 0.05}
                :confidence 0.92}}
 :usage {:input-tokens 304
         :output-tokens 18}}
```

CloJev preserves each question identifier. A keyword identifier produces a keyword answer key. A string identifier produces a string answer key.

## Structured state and instructions

State can be a string, map, or sequential collection.

```clojure
(clojev/system-one
  client
  {:message "I was charged twice."
   :account {:plan "pro"
             :region "eu"}}
  {:billing
   (clojev/noul "Is `message` about billing?")})
```

Instructions and criteria can contain JSON-compatible Clojure data.

```clojure
(clojev/noul
  {:potential-duplicate
   {:name "John Smith"
    :location "Oakland, California"
    :last-employer "Google"}
   :question
   "Is the resume for the same person as `potential_duplicate`?"}
  {:true "The records describe the same person."
   :false "The records describe different people."})
```

## Client options

The JVM and Jolt backend clients accept these common options:

```clojure
(http/client
  {:api-key "your-api-key"
   :model "jev-latest"
   :base-url "https://api.typesafe.ai"
   :timeout-ms 10000
   :headers {"X-Application" "support-router"}
   :retry {:max-retries 2
           :backoff-initial-ms 500
           :backoff-max-ms 5000
           :backoff-jitter 0.25
           :statuses #{408 429 500 502 503 504 529}
           :respect-retry-after? true
           :budget-ms 30000}})
```

Set `:max-retries` to `0` to disable retries. Set `:budget-ms` to `nil` to remove the total retry budget.

You can override selected options for one call:

```clojure
(clojev/system-one
  client
  state
  questions
  {:model "jev-latest"
   :timeout-ms 5000
   :extra-headers {"X-Trace-ID" "trace-123"}
   :retry {:max-retries 0}})
```

CloJev controls the `Authorization`, `Accept`, `Content-Type`, and `User-Agent` headers. Custom headers cannot replace these values.

## Replace the JVM HTTP backend

`clojev.http.jvm/client` accepts a `:request-fn`. The function performs one HTTP attempt.

The function receives this map:

```clojure
{:method :post
 :url "https://api.typesafe.ai/v1/systemone"
 :headers {"Authorization" "Bearer ..."
           "Content-Type" "application/json"}
 :body "{...}"
 :timeout-ms 10000}
```

The function must return this map:

```clojure
{:status 200
 :headers {"content-type" "application/json"}
 :body "{...}"}
```

Header names and values must be strings. Use lowercase response header names.

This example adapts `clj-http`:

```clojure
(require '[clj-http.client :as clj-http]
         '[clojure.string :as str]
         '[clojev.http.jvm :as http])

(defn normalize-headers [headers]
  (into {}
        (map (fn [[header-name value]]
               [(str/lower-case
                  (if (keyword? header-name)
                    (name header-name)
                    (str header-name)))
                (str (if (sequential? value)
                       (first value)
                       value))]))
        headers))

(defn clj-http-request!
  [{:keys [method url headers body timeout-ms]}]
  (try
    (let [response
          (clj-http/request
            {:method method
             :url url
             :headers headers
             :body body
             :as :text
             :throw-exceptions false
             :connection-timeout timeout-ms
             :socket-timeout timeout-ms})]
      {:status (:status response)
       :headers (normalize-headers (:headers response))
       :body (or (:body response) "")})
    (catch java.net.SocketTimeoutException error
      (throw
        (ex-info "HTTP request timed out"
                 {:type :clojev/timeout
                  :timeout-ms timeout-ms}
                 error)))
    (catch java.io.IOException error
      (throw
        (ex-info "HTTP connection failed"
                 {:type :clojev/connection-error}
                 error)))))

(def client
  (http/client {:request-fn clj-http-request!}))
```

Do not add retries inside the backend. CloJev applies its retry policy after each attempt.

For a reusable adapter, implement `clojev.transport/Transport` and pass the value as `:transport`.

```clojure
(require '[clojev.http.jvm :as http]
         '[clojev.transport :as transport])

(defrecord MyTransport [http-client]
  transport/Transport
  (-send! [_ request]
    (send-one-request! http-client request)))

(def client
  (http/client
    {:transport (->MyTransport my-http-client)}))
```

## Portable core

`clojev.core` and `clojev.transport` use portable Clojure source files. They contain no JVM HTTP implementation.

A runtime backend supplies two protocols:

- `clojev.transport/Transport` sends one normalized HTTP request.
- `clojev.transport/Platform` supplies time, sleep, random, and environment operations.

The JVM and Jolt namespaces supply these protocols. Another Clojure runtime can add a backend without changing `clojev.core`.

## Install and use with Jolt

Add CloJev and the Jolt support libraries to an alias:

```clojure
{:aliases
 {:jolt
  {:extra-deps
   {io.github.antlobach/clojev
    {:git/url "https://github.com/antlobach/clojev.git"
     :git/branch "main"}

    io.github.jolt-lang/time
    {:git/url "https://github.com/jolt-lang/time.git"
     :git/sha "abeee49974c2c4efa2fa75c96d9a379822c6815f"}

    jolt-lang/http-client
    {:git/url "https://github.com/jolt-lang/http-client"
     :git/sha "281689c9b6b54f3b09bb0fd3ca05dba4e54c6953"}}}}}
```

Set the API key:

```sh
export TYPESAFE_API_KEY="your-api-key"
```

Start Jolt with the alias:

```sh
jolt -A:jolt -M
```

Use the Jolt backend:

```clojure
(require '[clojev.core :as clojev]
         '[clojev.http.jolt :as http])

(def client (http/client))

(clojev/system-one
  client
  "Route this support request."
  {:department
   (clojev/choice
     "Which team must handle this request?"
     {"billing" nil
      "technical" nil
      "sales" nil})})
```

The Jolt backend uses `jolt-lang/http-client`. It does not use the JVM implementation.

## Errors

CloJev throws `ExceptionInfo` for request, API, response, and transport failures.

```clojure
(try
  (clojev/system-one client state questions)
  (catch clojure.lang.ExceptionInfo error
    (let [{:keys [type status request-id retry-after-ms]}
          (ex-data error)]
      (println type status request-id retry-after-ms))))
```

Common error types include:

| Type | Meaning |
|---|---|
| `:clojev/invalid-request` | Local request or configuration validation failed |
| `:clojev/authentication-error` | The API returned HTTP 401 |
| `:clojev/unprocessable-entity` | The API returned HTTP 422 |
| `:clojev/rate-limit-error` | The API returned HTTP 429 |
| `:clojev/internal-server-error` | The API returned an HTTP 5xx status |
| `:clojev/timeout` | One HTTP attempt exceeded its timeout |
| `:clojev/connection-error` | The backend did not receive an HTTP response |
| `:clojev/response-validation-error` | A successful response did not match the API contract |

API errors include available status, body, headers, endpoint, request ID, and retry delay data.

## Run the tests

```sh
clojure -M:test
```

Run the Jolt test with Jolt 0.8.9 or later:

```sh
jolt -A:jolt -Sdeps '{:paths ["src" "test"]}' -M -m clojev.jolt-test
```

The JVM suite uses a local HTTP server. It also runs six deterministic property-based fuzz tests with 1,000 generated cases per property. The tests cover constructor boundaries, request and response round trips, protected header casing, retry limits, and malformed response probabilities. These tests do not require a TypeSafe API key.

### Run a live API smoke test

The manual GitHub Actions workflow tests the real API from the JVM and Jolt. Store the API key as an encrypted repository secret, and then start the workflow:

```sh
gh secret set TYPESAFE_API_KEY
gh workflow run live-smoke.yml
```

The normal CI workflow does not use the live API or require an API key.

## License

CloJev uses the Apache License 2.0. See [LICENSE](LICENSE).

## Project status

CloJev is an unofficial client. Review the API behavior before you use it in a production system.
