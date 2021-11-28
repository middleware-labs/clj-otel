(ns example.manual-instrument.interceptor.average-service-async
  "Example application demonstrating using `clj-otel` to add telemetry to an
  asynchronous Pedestal HTTP service that is run without the OpenTelemetry
  instrumentation agent."
  (:require [clj-http.client :as client]
            [clojure.core.async :as async]
            [clojure.string :as str]
            [example.common-utils.core-async :as async']
            [io.pedestal.http :as http]
            [io.pedestal.http.route :as route]
            [ring.util.response :as response]
            [steffan-westcott.otel.api.trace.http :as trace-http]
            [steffan-westcott.otel.api.trace.span :as span]
            [steffan-westcott.otel.context :as context]))


(defn client-request
  "Make an asynchronous HTTP request using `clj-http`."
  [context request respond raise]

  ;; Manually create a client span with `context` as the parent context.
  ;; Context containing client span is assigned to `context*`. Client span is
  ;; ended when either a response or exception is returned.
  (span/async-span
    (trace-http/client-span-opts request {:parent context})
    (fn [context* respond* raise*]

      (let [;; Propagate context containing client span to remote
            ;; server by injecting headers. This enables span
            ;; correlation to make distributed traces.
            request' (update request :headers merge (context/->headers {:context context*}))]

        (client/request request'
                        (fn [response]

                          ;; Add HTTP response data to the client span.
                          (trace-http/add-client-span-response-data! response {:context context*})

                          (respond* response))
                        raise*)))
    respond
    raise))



(defn <client-request
  "Make an asynchronous HTTP request and return a channel of the response."
  [context request]
  (let [<ch (async/chan)
        put-ch #(async/put! <ch %)]
    (client-request context request put-ch put-ch)
    <ch))



(defn <get-sum
  "Get the sum of the nums and return a channel of the result."
  [context nums]
  (let [<response (<client-request context
                                   {:method           :get
                                    :url              "http://localhost:8081/sum"
                                    :query-params     {"nums" (str/join "," nums)}
                                    :async            true
                                    :throw-exceptions false})]
    (async'/go-try
      (let [response (async'/<? <response)]
        (if (= (:status response) 200)
          (Integer/parseInt (:body response))
          (throw (ex-info "sum-service failed" {:server-status (:status response)})))))))


(defn divide
  "Divides x by y."
  [context x y]

  ;; Wrap synchronous function body with an internal span. Context containing
  ;; internal span is assigned to `context*`.
  (span/with-span-binding [context* {:parent     context
                                     :name       "Calculating division"
                                     :attributes {:parameters [x y]}}]

    (Thread/sleep 10)
    (let [result (double (/ x y))]

      ;; Add more attributes to internal span
      (span/add-span-data! {:context    context*
                            :attributes {:result result}})

      result)))



(defn <average
  "Calculate the average of the nums and return a channel of the result."
  [context nums]

  ;; Start a new internal span that ends when the source channel (returned by
  ;; the body) closes or 3000 milliseconds have elapsed. Returns a dest channel
  ;; with buffer size 1. Values are piped from source to dest irrespective of
  ;; timeout. Context containing internal span is assigned to `context*`.
  (async'/<with-span-binding [context* {:parent     context
                                        :name       "Calculating average"
                                        :attributes {:nums nums}}]
    3000 1

    (let [<sum (<get-sum context* nums)]
      (async'/go-try
        (divide context* (async'/<? <sum) (count nums))))))



(defn <averages
  "Calculates the averages of the odd numbers and the even numbers of nums and
  returns a channel of the result."
  [context nums]
  (let [<odds-average (<average context (filter odd? nums))
        <evens-average (<average context (filter even? nums))]
    (async'/go-try
      (let [result {:odds  (async'/<? <odds-average)
                    :evens (async'/<? <evens-average)}]

        ;; Add event to span
        (span/add-span-data! {:context context
                              :event   {:name       "Finished calculations"
                                        :attributes result}})

        result))))



(defn <get-averages
  "Asynchronous handler for 'GET /average' request. Returns a channel of the
  HTTP response containing calculated averages of the `nums` query parameters."
  [{:keys [io.opentelemetry/server-span-context request] :as ctx}]

  ; Add data describing matched route to the server span.
  (trace-http/add-route-data! "/average" {:context server-span-context})

  (let [num-str (get-in request [:query-params :nums])
        nums (map #(Integer/parseInt %) (str/split num-str #","))
        <avs (<averages server-span-context nums)]
    (async'/go-try-response
      ctx
      (let [{:keys [odds evens]} (async'/<? <avs)]
        (response/response (str "Odds average: " odds " Evens average: " evens))))))


(def get-averages-interceptor
  "Interceptor for 'GET /average' route."
  {:name  ::get-averages
   :enter <get-averages})


(def routes
  "Route maps for the service."
  (route/expand-routes
    [[[
       ;; Wrap request handling of all routes. As this application is not run
       ;; with the OpenTelemetry instrumentation agent, create a server span
       ;; for each request. Because part of this service uses asynchronous
       ;; processing, the current context is not set on each request.
       "/" (trace-http/server-span-interceptors {:create-span?         true
                                                 :set-current-context? false
                                                 :server-name          "average"})

       ["/average" {:get 'get-averages-interceptor}]]]]))



(def service-map
  "Pedestal service map for average HTTP service."
  {::http/routes routes
   ::http/type   :jetty
   ::http/port   8080
   ::http/join?  false})



(defonce ^{:doc "average-service server instance"} server
         (http/start (http/create-server service-map)))