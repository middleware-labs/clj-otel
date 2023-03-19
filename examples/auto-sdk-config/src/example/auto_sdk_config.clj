(ns example.auto-sdk-config
  (:require [middleware-dev.clj-otel.api.trace.span :as span]
            [middleware-dev.clj-otel.instrumentation.runtime-metrics :as runtime-metrics]))


(defn square
  "Returns the square of a number."
  [n]
  (span/with-span! {:name       "squaring"
                    :attributes {:app.square/n n}}
    (Thread/sleep 500)
    (span/add-span-data! {:event {:name "my event"}})
    (* n n)))

;;;;;;;;;;;;;

(defonce ^{:doc "JVM metrics registration"} _jvm-reg (runtime-metrics/register!))

(square 9)

