;!zprint {:style [:respect-nl]}

(ns example.programmatic_sdk_config
  "An example application demonstrating programmatic configuration,
  initialisation and shutdown of the OpenTelemetry SDK."
  #_{:clj-kondo/ignore [:unsorted-required-namespaces]}
  (:require

   ;; Require desired span exporters
   [middleware-dev.clj-otel.exporter.otlp.grpc.trace :as otlp-grpc-trace]
   ;[middleware-dev.clj-otel.exporter.otlp.http.trace :as otlp-http-trace]
   ;[middleware-dev.clj-otel.exporter.jaeger-grpc :as jaeger-grpc]
   ;[middleware-dev.clj-otel.exporter.jaeger-thrift :as jaeger-thrift]
   ;[middleware-dev.clj-otel.exporter.zipkin :as zipkin]
   ;[middleware-dev.clj-otel.exporter.logging :as logging]
   ;[middleware-dev.clj-otel.exporter.logging-otlp :as logging-otlp]

   [middleware-dev.clj-otel.api.trace.span :as span]
   [middleware-dev.clj-otel.resource.resources :as res]
   [middleware-dev.clj-otel.sdk.otel-sdk :as sdk]))

(defn init-otel!
  "Configure and initialise the OpenTelemetry SDK as the global OpenTelemetry
  instance used by the application. This function should be evaluated before
  performing any OpenTelemetry API operations such as tracing. This function
  may be evaluated once only, any attempts to evaluate it more than once will
  result in error."
  []
  (sdk/init-otel-sdk!

   ;; The service name is the minimum resource information.
   "example-app"

   {;; The collection of additional resources are merged with the service name
    ;; to form information about the entity for which telemetry is recorded.
    ;; Here the additional resources provide information on the host, OS,
    ;; process and JVM.
    :resources       [(res/host-resource)
                      (res/os-resource)
                      (res/process-resource)
                      (res/process-runtime-resource)]

    ;; Configuration options for the context propagation, sampling, batching
    ;; and export of traces. Here we configure export to a local Jaeger server
    ;; with default options. The exported spans are batched by default.
    :tracer-provider
    {:span-processors

     ;; Configure selected span exporter(s). See span exporter docstrings for
     ;; further configuration options.
     [{:exporters [
                   ;; Export spans to locally deployed OpenTelemetry Collector
                   ;; via gRPC
                   (otlp-grpc-trace/span-exporter)

                   ;; Export spans to locally deployed OpenTelemetry Collector
                   ;; via HTTP
                   ; (otlp-http-trace/span-exporter)

                   ;; Export spans to locally deployed Jaeger via gRPC
                   ; (jaeger-grpc/span-exporter)

                   ;; Export spans to locally deployed Jaeger via Thrift
                   ; (jaeger-thrift/span-exporter)

                   ;; Export spans to locally deployed Zipkin
                   ; (zipkin/span-exporter)

                   ;; Export spans to Honeycomb using OTLP via gRPC
                   ;(otlp-grpc-trace/span-exporter
                   ;  {:endpoint "https://api.honeycomb.io:443"
                   ;   :headers  {"x-honeycomb-team"    "YOUR_HONEYCOMB_TEAM_API_KEY"
                   ;              "x-honeycomb-dataset" "YOUR_HONEYCOMB_DATASET"}})

                   ;; Export spans to Honeycomb using OTLP via HTTP
                   ;(otlp-http-trace/span-exporter
                   ;  {:endpoint "https://api.honeycomb.io:443"
                   ;   :headers  {"x-honeycomb-team"    "YOUR_HONEYCOMB_TEAM_API_KEY"
                   ;              "x-honeycomb-dataset" "YOUR_HONEYCOMB_DATASET"}})

                   ;; Export spans to Lightstep using OTLP via gRPC
                   ;(otlp-grpc-trace/span-exporter
                   ;  {:endpoint "https://ingest.lightstep.com:443"
                   ;   :headers  {"lightstep-access-token" "YOUR_LIGHTSTEP_ACCESS_TOKEN"}})

                   ;; Export spans to java.util.logging (used for debugging
                   ;; only)
                   ;(logging/span-exporter)

                   ;; Export spans to java.util.logging in OTLP JSON format
                   ;; (used for debugging only)
                   ;(logging-otlp/span-exporter)

                  ]}]}}))

(defn close-otel!
  "Shut down OpenTelemetry SDK processes. This should be called before the
  application exits."
  []
  (sdk/close-otel-sdk!))

(defn square
  "Returns the square of a number."
  [n]
  (span/with-span! {:name "squaring"}
    (Thread/sleep 500)
    (* n n)))

(comment
  (init-otel!)    ; once only
  (square 7)
  (close-otel!)
)
