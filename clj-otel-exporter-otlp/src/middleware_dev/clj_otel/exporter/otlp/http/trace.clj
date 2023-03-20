(ns middleware-dev.clj-otel.exporter.otlp.http.trace
  "Span data exporter using OpenTelemetry Protocol via HTTP."
  (:require [middleware-dev.clj-otel.util :as util])
  (:import (io.opentelemetry.exporter.otlp.http.trace OtlpHttpSpanExporter
                                                      OtlpHttpSpanExporterBuilder)))

(defn- add-headers
  ^OtlpHttpSpanExporterBuilder [builder headers]
  (reduce-kv #(.addHeader ^OtlpHttpSpanExporterBuilder %1 %2 %3) builder headers))

(defn span-exporter
  "Returns a span exporter that sends span data using OTLP via HTTP, using
  OpenTelemetry's protobuf model. May take an option map as follows:

  | key                       | description |
  |---------------------------|-------------|
  |`:endpoint`                | OTLP endpoint, must start with `\"http://\"` or `\"https://\"` and include the full path (default: `\"http://localhost:4318/v1/traces\"`).
  |`:headers`                 | HTTP headers to add to request (default: `{}`).
  |`:trusted-certificates-pem`| `^bytes` X.509 certificate chain in PEM format for verifying servers when TLS enabled (default: system default trusted certificates).
  |`:client-private-key-pem`  | `^bytes` private key in PEM format for verifying client when TLS enabled.
  |`:client-certificates-pem` | `^bytes` X.509 certificate chain in PEM format for verifying client when TLS enabled.
  |`:compression-method`      | Method used to compress payloads, `\"gzip\"` or `\"none\"` (default: `\"none\"`).
  |`:timeout`                 | Maximum time to wait for export of a batch of spans. Value is either a `Duration` or a vector `[amount ^TimeUnit unit]` (default: 10s).
  |`:meter-provider`          | ^MeterProvider to collect metrics related to export (default: metrics not collected)."
  ([]
   (span-exporter {}))
  ([{:keys [endpoint headers trusted-certificates-pem client-private-key-pem client-certificates-pem
            compression-method timeout meter-provider]}]
   (let [builder (cond-> (OtlpHttpSpanExporter/builder)
                   endpoint           (.setEndpoint endpoint)
                   headers            (add-headers headers)
                   trusted-certificates-pem (.setTrustedCertificates trusted-certificates-pem)
                   (and client-private-key-pem client-certificates-pem) (.setClientTls
                                                                         client-private-key-pem
                                                                         client-certificates-pem)
                   compression-method (.setCompression compression-method)
                   timeout            (.setTimeout (util/duration timeout))
                   meter-provider     (.setMeterProvider meter-provider))]
     (.build builder))))
