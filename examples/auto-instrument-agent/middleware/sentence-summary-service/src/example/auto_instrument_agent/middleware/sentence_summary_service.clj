(ns example.auto-instrument-agent.middleware.sentence-summary-service
  "Example application demonstrating using `clj-otel` to add telemetry to a
  synchronous Ring HTTP service that is run with the OpenTelemetry
  instrumentation agent."
  (:require [clj-http.client :as client]
            [clojure.string :as str]
            [example.common-utils.middleware :as middleware]
            [muuntaja.core :as m]
            [reitit.ring :as ring]
            [reitit.ring.middleware.exception :as exception]
            [reitit.ring.middleware.muuntaja :as muuntaja]
            [reitit.ring.middleware.parameters :as parameters]
            [ring.adapter.jetty :as jetty]
            [ring.util.response :as response]
            [middleware-dev.clj-otel.api.trace.http :as trace-http]
            [middleware-dev.clj-otel.api.trace.span :as span]))

(defn get-word-length
  "Get the length of `word`."
  [word]

  ;; Apache HttpClient request is automatically wrapped in a client span
  ;; created by the OpenTelemetry instrumentation agent. The agent also
  ;; propagates the context containing the client span to the remote HTTP
  ;; server by injecting headers into the request.
  (let [response (client/get "http://localhost:8081/length"
                             {:throw-exceptions false
                              :query-params     {"word" word}})
        status   (:status response)]

    (if (= 200 status)
      (Integer/parseInt (:body response))
      (throw (ex-info "Unexpected HTTP response"
                      {:type          ::ring/response
                       :response      {:status status
                                       :body   "Unexpected HTTP response"}
                       :service/error :service.errors/unexpected-http-response})))))



(defn word-lengths
  "Get the word lengths."
  [words]

  ;; Wrap synchronous function body with an internal span.
  (span/with-span! {:name       "Getting word lengths"
                    :attributes {:system/words words}}

    ;; Use `doall` to force lazy sequence to be realized within span
    (doall (map get-word-length words))))



(defn summary
  "Returns a summary of the given word lengths."
  [lengths]

  ;; Wrap synchronous function body with an internal span.
  (span/with-span! {:name       "Building sentence summary"
                    :attributes {:system/word-lengths lengths}}

    (Thread/sleep 25)
    (let [result {:word-count      (count lengths)
                  :shortest-length (apply min lengths)
                  :longest-length  (apply max lengths)}]

      ;; Add more attributes to internal span
      (span/add-span-data! {:attributes {:service.sentence-summary.summary/word-count (:word-count
                                                                                       result)}})

      result)))



(defn build-summary
  "Builds a summary of the words in the sentence."
  [sentence]
  (let [words   (str/split sentence #"\s+")
        lengths (word-lengths words)]
    (summary lengths)))



(defn get-summary-handler
  "Synchronous Ring handler for `GET /summary` request. Returns an HTTP
  response containing a summary of the words in the given sentence."
  [{:keys [query-params]}]
  (let [sentence (get query-params "sentence")
        summary  (build-summary sentence)]
    (response/response (str summary))))



(def handler
  "Ring handler for all requests."
  (ring/ring-handler (ring/router
                      ["/summary"
                       {:name ::summary
                        :get  get-summary-handler}]
                      {:data {:muuntaja   m/instance
                              :middleware [;; Add route data
                                           middleware/wrap-reitit-route

                                           parameters/parameters-middleware
                                           muuntaja/format-middleware exception/exception-middleware

                                           ;; Add exception event
                                           middleware/wrap-exception-event]}})
                     (ring/create-default-handler)

                     ;; Wrap handling of all requests, including those which have no matching route.
                     ;; As this application is run with the OpenTelemetry instrumentation agent, a
                     ;; server span will be provided by the agent and there is no need to create
                     ;; another one.
                     {:middleware [[trace-http/wrap-server-span {:create-span? false}]]}))



(defonce ^{:doc "sentence-summary-service server instance"} server
         (jetty/run-jetty #'handler
                          {:port  8080
                           :join? false}))
