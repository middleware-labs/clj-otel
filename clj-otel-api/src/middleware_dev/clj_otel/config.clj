(ns ^:no-doc middleware-dev.clj-otel.config
  "Configuration of clj-otel library."
  (:require [clojure.java.io :as io]))

(def config
  "Configuration map of the clj-otel library."
  (-> "middleware_dev/clj_otel/config.edn"
      io/resource
      slurp
      read-string))
