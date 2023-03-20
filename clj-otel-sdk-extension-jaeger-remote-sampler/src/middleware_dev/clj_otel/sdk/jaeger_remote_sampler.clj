(ns middleware-dev.clj-otel.sdk.jaeger-remote-sampler
  "`Sampler` that implements Jaeger remote sampler type."
  (:require [middleware-dev.clj-otel.sdk.otel-sdk :as sdk]
            [middleware-dev.clj-otel.util :as util])
  (:import (io.opentelemetry.sdk.extension.trace.jaeger.sampler JaegerRemoteSampler)))

(defn jaeger-remote-sampler
  "Returns a `JaegerRemoteSampler`, a sampler that periodically obtains
  configuration from a remote Jaeger server. Takes an option map as follows:

  | key                       | description |
  |---------------------------|-------------|
  |`:service-name`            | Service name to be used by this sampler, required.
  |`:endpoint`                | Jaeger endpoint to connect to (default: `\"localhost:14250\"`).
  |`:trusted-certificates-pem`| `^bytes` X.509 certificate chain in PEM format for verifying servers when TLS enabled (default: system default trusted certificates).
  |`:client-private-key-pem`  | `^bytes` private key in PEM format for verifying client when TLS enabled.
  |`:client-certificates-pem` | `^bytes` X.509 certificate chain in PEM format for verifying client when TLS enabled.
  |`:polling-interval`        | Polling interval for configuration updates. Value is either a `Duration` or a vector `[amount ^TimeUnit unit]` (default: 60s).
  |`:initial-sampler`         | Initial sampler that is used before sampling configuration is obtained (default: `{:parent-based {:root {:ratio 0.001}}}`)."
  [{:keys [service-name endpoint trusted-certificates-pem client-private-key-pem
           client-certificates-pem polling-interval initial-sampler]}]
  (let [builder (cond-> (.setServiceName (JaegerRemoteSampler/builder) service-name)
                  endpoint         (.setEndpoint endpoint)
                  trusted-certificates-pem (.setTrustedCertificates trusted-certificates-pem)
                  (and client-private-key-pem client-certificates-pem) (.setClientTls
                                                                        client-private-key-pem
                                                                        client-certificates-pem)
                  polling-interval (.setPollingInterval (util/duration polling-interval))
                  initial-sampler  (.setInitialSampler (sdk/as-Sampler initial-sampler)))]
    (.build builder)))
