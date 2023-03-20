(ns example.manual-instrument.interceptor.average-service
  "Example application demonstrating using `clj-otel` to add telemetry to a
  synchronous Pedestal HTTP service that is run without the OpenTelemetry
  instrumentation agent."
  (:require [clj-http.client :as client]
            [clojure.string :as str]
            [example.common-utils.interceptor :as utils-interceptor]
            [io.pedestal.http :as http]
            [io.pedestal.http.route :as route]
            [io.pedestal.interceptor :as interceptor]
            [ring.util.response :as response]
            [middleware-dev.clj-otel.api.metrics.http.server :as metrics-http-server]
            [middleware-dev.clj-otel.api.trace.http :as trace-http]
            [middleware-dev.clj-otel.api.trace.span :as span]
            [middleware-dev.clj-otel.context :as context]
            [middleware-dev.clj-otel.instrumentation.runtime-metrics :as runtime-metrics]))


(defn client-request
  "Perform a synchronous HTTP request using `clj-http`."
  [request]

  ;; Wrap the synchronous body in a new client span.
  (span/with-span! (trace-http/client-span-opts request)

    (let [;; Propagate context containing client span to remote
          ;; server by injecting headers. This enables span
          ;; correlation to make distributed traces.
          request' (update request :headers merge (context/->headers))

          response (client/request request')]

      ;; Add HTTP response data to the client span.
      (trace-http/add-client-span-response-data! response)

      response)))



(defn get-sum
  "Get the sum of the nums."
  [nums]
  (let [response (client-request {:method       :get
                                  :url          "http://localhost:8081/sum"
                                  :query-params {"nums" (str/join "," nums)}
                                  :throw-exceptions false})
        status   (:status response)]
    (if (= 200 status)
      (Integer/parseInt (:body response))
      (throw (ex-info (str status " HTTP response")
                      {:http.response/status status
                       :service/error        :service.errors/unexpected-http-response})))))



(defn divide
  "Divides x by y."
  [x y]

  ;; Wrap synchronous function body with an internal span.
  (span/with-span! {:name       "Calculating division"
                    :attributes {:service.average.divide/parameters [x y]}}

    (Thread/sleep 10)
    (let [result (double (/ x y))]

      ;; Add more attributes to internal span
      (span/add-span-data! {:attributes {:service.average.divide/result result}})

      result)))



(defn average
  "Calculate the average of the nums."
  [nums]
  (let [sum (get-sum nums)]
    (divide sum (count nums))))



(defn averages
  "Calculates the averages of the odd numbers and the even numbers of nums and
  returns a channel of the result."
  [nums]
  (let [odds          (filter odd? nums)
        evens         (filter even? nums)
        odds-average  (when (seq odds)
                        (average odds))
        evens-average (when (seq evens)
                        (average evens))
        result        {:odds  odds-average
                       :evens evens-average}]

    ;; Add event to span
    (span/add-span-data! {:event {:name       "Finished calculations"
                                  :attributes {:system.averages/odds  odds-average
                                               :system.averages/evens evens-average}}})

    result))



(defn get-averages-handler
  "Synchronous handler for 'GET /average' request. Returns an HTTP response
  containing calculated averages of the odd and even numbers."
  [request]
  (let [{:keys [query-params]} request
        num-str  (get query-params :nums)
        num-strs (->> (str/split num-str #",")
                      (map str/trim)
                      (filter seq))
        nums     (map #(Integer/parseInt %) num-strs)
        avs      (averages nums)]
    (response/response (str avs))))



(def routes
  "Route maps for the service."
  (route/expand-routes [[["/" ^:interceptors [(utils-interceptor/exception-response-interceptor)]
                          ["/average" {:get 'get-averages-handler}]]]]))



(def service-map
  "Pedestal service map for average HTTP service."
  {::http/routes routes
   ::http/type   :jetty
   ::http/port   8080
   ::http/join?  false})



(defn update-default-interceptors
  "Returns `default-interceptors` with added interceptors for HTTP server
  span support."
  [default-interceptors]
  (map interceptor/interceptor
       (concat (;; As this application is not run with the OpenTelemetry instrumentation
                ;; agent, create a server span for each request. Because all request
                ;; processing for this service is synchronous, the current context is set
                ;; for each request.
                trace-http/server-span-interceptors {:create-span?         true
                                                     :set-current-context? true})

               ;; Add metric that records the number of active HTTP requests
               [(metrics-http-server/active-requests-interceptor)]

               ;; Default Pedestal interceptor stack
               default-interceptors

               ;; Adds matched route data to server spans
               [(trace-http/route-interceptor)]

               ;; Adds metrics that include http.route attribute
               (metrics-http-server/metrics-by-route-interceptors))))



(defn service
  "Returns an initialised service map ready for creating an HTTP server."
  [service-map]
  (-> service-map
      (http/default-interceptors)
      (update ::http/interceptors update-default-interceptors)
      (http/create-server)))



;; Register measurements that report metrics about the JVM runtime. These measurements cover
;; buffer pools, classes, CPU, garbage collector, memory pools and threads.
(defonce ^{:doc "JVM metrics registration"} _jvm-reg (runtime-metrics/register!))



(defonce ^{:doc "average-service server instance"} server (http/start (service service-map)))
