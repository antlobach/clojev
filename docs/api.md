# CloJev API reference

CloJev is an independent community SDK. TypeSafe does not develop, sponsor, endorse, or support this project.

This reference describes the public namespaces in CloJev.

## `clojev.core`

Require the portable core:

```clojure
(require '[clojev.core :as clojev])
```

### Constants

#### `default-base-url`

```clojure
clojev/default-base-url
;; => "https://api.typesafe.ai"
```

The default TypeSafe API root.

#### `default-model`

```clojure
clojev/default-model
;; => "jev-latest"
```

The default model alias.

#### `default-timeout-ms`

```clojure
clojev/default-timeout-ms
;; => 10000
```

The default timeout for each HTTP attempt.

### `retry-policy`

```clojure
(clojev/retry-policy)
(clojev/retry-policy overrides)
```

Returns a validated retry policy.

Default value:

```clojure
{:max-retries 2
 :backoff-initial-ms 500
 :backoff-max-ms 5000
 :backoff-jitter 0.25
 :statuses #{408 429 500 ... 599}
 :respect-retry-after? true
 :budget-ms 30000}
```

Options:

| Key | Type | Meaning |
|---|---|---|
| `:max-retries` | Non-negative integer | Maximum attempts after the first attempt |
| `:backoff-initial-ms` | Non-negative number | Delay before the first retry |
| `:backoff-max-ms` | Non-negative number | Maximum exponential delay |
| `:backoff-jitter` | Number from 0 to 1 | Maximum fraction removed from each delay |
| `:statuses` | Set of integers | HTTP statuses that permit a retry |
| `:respect-retry-after?` | Boolean | Use server retry headers when present |
| `:budget-ms` | Positive integer or `nil` | Total time budget for attempts and delays |

Set `:max-retries` to `0` to disable retries. Set `:budget-ms` to `nil` to remove the total budget.

### `client`

```clojure
(clojev/client opts)
```

Creates a portable client. Runtime-specific applications should use `clojev.http.jvm/client` or `clojev.http.jolt/client`.

Required options:

| Key | Type | Meaning |
|---|---|---|
| `:transport` | `clojev.transport/Transport` | Sends one HTTP attempt |
| `:platform` | `clojev.transport/Platform` | Supplies runtime operations |

Optional options:

| Key | Default | Meaning |
|---|---|---|
| `:api-key` | `TYPESAFE_API_KEY` | TypeSafe API key |
| `:model` | Environment or `"jev-latest"` | Default request model |
| `:base-url` | Environment or public API root | API root without the endpoint path |
| `:timeout-ms` | `10000` | Timeout for each HTTP attempt |
| `:headers` | `{}` | Additional request headers |
| `:retry` | Default retry policy | Retry policy overrides |

The client reads `TYPESAFE_API_KEY`, `TYPESAFE_DEFAULT_MODEL`, and `TYPESAFE_BASE_URL` through the platform.

### `noul`

```clojure
(clojev/noul instructions)
(clojev/noul instructions criteria)
```

Creates a yes or no question.

`instructions` can be a string, map, or sequential value. `criteria` is an optional map.

```clojure
(clojev/noul
  "Does this message convey urgency?"
  {:true "The message is time-sensitive."
   :false "The message has no time constraint."})
```

Returns:

```clojure
{:type "noul"
 :instructions "Does this message convey urgency?"
 :criteria {:true "The message is time-sensitive."
            :false "The message has no time constraint."}}
```

### `choice`

```clojure
(clojev/choice instructions criteria)
```

Creates a question that selects one named option. `criteria` must contain from 1 through 255 options.

```clojure
(clojev/choice
  "Which team must handle this request?"
  {"billing" "Payments and refunds"
   "technical" "Bugs and integrations"
   "sales" "Pricing and accounts"})
```

Returns:

```clojure
{:type "choice"
 :instructions "Which team must handle this request?"
 :criteria {"billing" "Payments and refunds"
            "technical" "Bugs and integrations"
            "sales" "Pricing and accounts"}}
```

### `score`

```clojure
(clojev/score instructions criteria)
```

Creates an ordered score question. `criteria` must contain from 2 through 10 levels.

```clojure
(clojev/score
  "How frustrated is the customer?"
  ["Calm" "Frustrated" "Very angry"])
```

Returns:

```clojure
{:type "score"
 :instructions "How frustrated is the customer?"
 :criteria ["Calm" "Frustrated" "Very angry"]}
```

### `system-one`

```clojure
(clojev/system-one client state questions)
(clojev/system-one client state questions opts)
```

Sends one System One request. The function retries failed attempts according to the effective retry policy.

Arguments:

| Argument | Type | Meaning |
|---|---|---|
| `client` | CloJev client map | Client configuration and runtime backend |
| `state` | String, map, or sequential value | Content that the model evaluates |
| `questions` | Non-empty map | Named Noul, Choice, or Score questions |
| `opts` | Map | Options for this call |

Call options:

| Key | Meaning |
|---|---|
| `:model` | Replaces the client model for this call |
| `:timeout-ms` | Replaces the attempt timeout for this call |
| `:retry` | Merges with the client retry policy |
| `:extra-headers` | Adds request headers for this call |

Question identifiers can be strings or keywords. CloJev preserves each identifier in the answer map.

CloJev sends requests to this path:

```text
/v1/systemone
```

#### Result

```clojure
{:model "jev-1.13.0"
 :answers {question-id answer}
 :usage {:input-tokens 304
         :output-tokens 18}}
```

#### Noul answer

```clojure
{:type :noul
 :noul 0.95}
```

`:noul` is a number from 0 through 1. It is the probability that the answer is yes.

#### Choice answer

```clojure
{:type :choice
 :choice "billing"
 :probabilities {"billing" 0.88
                 "technical" 0.12}
 :confidence 0.81}
```

`:choice` contains the option with the highest probability. `:confidence` is a number from 0 through 1.

#### Score answer

```clojure
{:type :score
 :score 1.05
 :legend {"0" "Calm"
          "1" "Frustrated"
          "2" "Very angry"}
 :probabilities {"0" 0.0
                 "1" 0.95
                 "2" 0.05}
 :confidence 0.92}
```

`:score` is the probability-weighted value across the ordered levels.

## `clojev.http.jvm`

Require the JVM backend:

```clojure
(require '[clojev.http.jvm :as http])
```

### `platform`

```clojure
(http/platform)
```

Returns the standard JVM implementation of `clojev.transport/Platform`.

### `jdk-transport`

```clojure
(http/jdk-transport)
(http/jdk-transport opts)
```

Creates the built-in `java.net.http.HttpClient` transport.

Options:

| Key | Meaning |
|---|---|
| `:http-client` | Existing `java.net.http.HttpClient` instance |
| `:connect-timeout-ms` | Connection timeout for a new client |

The transport does not follow redirects.

### `client`

```clojure
(http/client)
(http/client opts)
```

Creates a CloJev client for the JVM. The function adds the JVM platform and a default JDK transport.

Additional options:

| Key | Meaning |
|---|---|
| `:http-client` | Existing JDK HTTP client |
| `:connect-timeout-ms` | Timeout for new connections |
| `:transport` | Replacement `Transport` implementation |
| `:request-fn` | Function adapter for another HTTP client |
| `:platform` | Replacement `Platform` implementation |

Do not pass both `:transport` and `:request-fn`.

## `clojev.http.jolt`

Require the Jolt backend from Jolt:

```clojure
(require '[clojev.http.jolt :as http])
```

### `platform`

```clojure
(http/platform)
```

Returns the standard Jolt implementation of `clojev.transport/Platform`.

### `jolt-transport`

```clojure
(http/jolt-transport)
```

Creates a transport that uses `jolt-lang/http-client`.

### `client`

```clojure
(http/client)
(http/client opts)
```

Creates a CloJev client for Jolt. The function adds the Jolt platform and transport.

You can pass `:transport`, `:request-fn`, or `:platform` to replace a Jolt runtime component.

Do not pass both `:transport` and `:request-fn`.

## `clojev.transport`

Require the transport contracts:

```clojure
(require '[clojev.transport :as transport])
```

### `Transport`

```clojure
(transport/-send! transport request)
```

A transport performs one synchronous HTTP attempt.

Request map:

```clojure
{:method :post
 :url "https://api.typesafe.ai/v1/systemone"
 :headers {"Header-Name" "value"}
 :body "JSON text"
 :timeout-ms 10000}
```

Response map:

```clojure
{:status 200
 :headers {"content-type" "application/json"}
 :body "JSON text"
 :retry-after-ms 1000}
```

`:retry-after-ms` is optional. Response header names and values must be strings. Use lowercase response header names.

A transport must not retry requests. The portable core owns the retry policy.

A transport must throw `ExceptionInfo` with one of these types when it receives no HTTP response:

```clojure
:clojev/timeout
:clojev/connection-error
```

### `function-transport`

```clojure
(transport/function-transport request-fn)
```

Wraps a normalized request function in a `Transport` implementation.

### `send!`

```clojure
(transport/send! transport request)
```

Sends one request through a `Transport` implementation.

### `Platform`

A platform supplies runtime operations to the portable core.

```clojure
(transport/-now-ms platform)
(transport/-sleep-ms! platform milliseconds)
(transport/-random-double platform)
(transport/-getenv platform name)
```

Public wrapper functions are also available:

```clojure
(transport/now-ms platform)
(transport/sleep-ms! platform milliseconds)
(transport/random-double platform)
(transport/getenv platform name)
```

## Error data

CloJev uses `ExceptionInfo`. Read structured data with `ex-data`.

```clojure
(try
  (clojev/system-one client state questions)
  (catch clojure.lang.ExceptionInfo error
    (ex-data error)))
```

### Error types

| Type | Condition |
|---|---|
| `:clojev/invalid-request` | Local request or configuration validation failed |
| `:clojev/bad-request` | HTTP 400 |
| `:clojev/authentication-error` | HTTP 401 |
| `:clojev/permission-denied` | HTTP 403 |
| `:clojev/not-found` | HTTP 404 |
| `:clojev/unprocessable-entity` | HTTP 422 |
| `:clojev/rate-limit-error` | HTTP 429 |
| `:clojev/internal-server-error` | HTTP 500 through 599 |
| `:clojev/api-error` | Another unsuccessful HTTP status |
| `:clojev/timeout` | One HTTP attempt exceeded its timeout |
| `:clojev/connection-error` | The backend received no HTTP response |
| `:clojev/response-validation-error` | A successful response failed contract validation |

API errors can include these keys:

```clojure
{:type :clojev/rate-limit-error
 :status 429
 :body {"error" "rate limited"}
 :headers {"retry-after" "1"}
 :endpoint "https://api.typesafe.ai/v1/systemone"
 :request-id "request-id"
 :retry-after-ms 1000}
```

Response validation errors also include `:field-path`. The value identifies the invalid response field.
