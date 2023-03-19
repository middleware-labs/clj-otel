;!zprint {:style [:respect-nl]}

(ns build
  "Build scripts for clj-otel-* libraries, examples and tutorials.

  For example, to lint all clj-otel-* libraries:

  clojure -T:build lint

  To see a description of all build scripts:

  clojure -A:deps -T:build help/doc
  "
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.build.api :as b]
            [org.corfield.build :as cb])
  (:import (java.nio.file FileSystems)))

(def ^:private group-id
  "org.clojars.middleware-dev")

(def ^:private version
  "0.1.5-SNAPSHOT")

;; Later artifacts in this vector may depend on earlier artifacts
(def ^:private artifact-ids
  ["clj-otel-api"
   "clj-otel-sdk"
   "clj-otel-contrib-aws-resources"
   "clj-otel-contrib-aws-xray-propagator"
   "clj-otel-sdk-extension-jaeger-remote-sampler"
   "clj-otel-extension-trace-propagators"
   "clj-otel-exporter-jaeger-grpc"
   "clj-otel-exporter-jaeger-thrift"
   "clj-otel-exporter-logging"
   "clj-otel-exporter-logging-otlp"
   "clj-otel-exporter-otlp"
   "clj-otel-exporter-zipkin"
   "clj-otel-instrumentation-resources"
   "clj-otel-instrumentation-runtime-metrics"])

(def ^:private demo-project-paths
  ["examples/auto-instrument-agent/interceptor/planet-service"
   "examples/auto-instrument-agent/interceptor/solar-system-service"
   "examples/auto-instrument-agent/middleware/sentence-summary-service"
   "examples/auto-instrument-agent/middleware/word-length-service"
   "examples/auto-sdk-config"
   "examples/common-utils/core-async"
   "examples/common-utils/interceptor"
   "examples/common-utils/middleware"
   "examples/manual-instrument/interceptor/average-service"
   "examples/manual-instrument/interceptor/sum-service"
   "examples/manual-instrument/middleware/puzzle-service"
   "examples/manual-instrument/middleware/random-word-service"
   "examples/programmatic-sdk-config"
   "tutorial/instrumented"])

(def ^:private project-paths
  (concat artifact-ids demo-project-paths))

(defn- group-artifact-id
  [artifact-id]
  (str group-id "/" artifact-id))

(def ^:private group-artifact-ids
  (map group-artifact-id artifact-ids))

(def ^:private snapshot?
  (str/ends-with? version "-SNAPSHOT"))

(defn- checked-process
  [params]
  (let [result (b/process params)]
    (if (zero? (:exit result))
      result
      (throw (ex-info "Process returned non-zero exit code." (assoc result :params params))))))

(defn- head-sha-1
  []
  (b/git-process {:git-args "rev-parse HEAD"}))

(defn- println>
  [x & args]
  (apply println args)
  x)

(defn- glob-match
  "Returns a predicate which returns true if a single glob `pattern` matches a
  `Path` arg and false otherwise. If given several patterns, returns true if
  any match and false if none match."
  ([pattern]
   (let [matcher (.getPathMatcher (FileSystems/getDefault) (str "glob:" pattern))]
     #(.matches matcher %)))
  ([pattern & pats]
   (let [f (apply some-fn (map glob-match (cons pattern pats)))]
     #(boolean (f %)))))

(defn- file-match
  "Returns path strings of files in directory tree `root`, with relative paths
  filtered by `pred`.
  Example: Match all *.clj files in directory tree `src`
  `(file-match \"src\" (glob-match \"**.clj\"))`"
  [root pred]
  (let [root-file (io/file root)
        root-path (.toPath root-file)]
    (->> root-file
         file-seq
         (filter #(.isFile %))
         (filter #(pred (.relativize root-path (.toPath %))))
         (map #(str (.normalize (.toPath %)))))))

(defn- globs
  "Returns path strings of files in directory tree `root` with relative paths
  matching any of glob `patterns`."
  [root & patterns]
  (file-match root (apply glob-match patterns)))

(defn- jar-artifact
  [opts artifact-id]
  (b/set-project-root! artifact-id)
  (-> opts
      (assoc :lib     (symbol group-id artifact-id)
             :version version
             :tag     (head-sha-1)
             :src-pom "template/pom.xml"
             :basis   (b/create-basis {:aliases (when snapshot?
                                                  [:snapshot])}))
      cb/clean
      cb/jar))

(defn- install-artifact
  [opts artifact-id]
  (-> opts
      (jar-artifact artifact-id)
      (println> (str "Installing " (group-artifact-id artifact-id)))
      cb/install))

(defn- deploy-artifact
  [opts artifact-id]
  (-> opts
      (install-artifact artifact-id)
      (println> (str "Deploying " (group-artifact-id artifact-id)))
      cb/deploy))

(defn- tag-release
  [tag]
  (println (str "Creating and pushing tag " tag))
  (checked-process {:command-args ["git" "tag" "-a" "-m" (str "Release " tag) tag]})
  (checked-process {:command-args ["git" "push" "origin" tag]}))

(defn install
  "Ensure all clj-otel-* library JAR files are built and installed in the local
  Maven repository. The libraries are processed in an order such that later
  libraries may depend on earlier ones."
  [opts]
  (doseq [artifact-id artifact-ids]
    (install-artifact opts artifact-id)))

(defn deploy
  "Ensure all clj-otel-* library JAR files are built, installed in the local
  Maven repository and deployed to Clojars. The libraries are processed in an
  order such that later libraries may depend on earlier ones.

  For non-SNAPSHOT versions, a git tag with the version name is created and
  pushed to the origin repository."
  [opts]
  (doseq [artifact-id artifact-ids]
    (deploy-artifact opts artifact-id))
  (when-not snapshot?
    (tag-release version)))

(defn lint
  "Lint all clj-otel-* libraries, example applications and tutorial source
  files using clj-kondo. Assumes a working installation of `clj-kondo`
  executable binary."
  [opts]
  (let [src-paths (map #(str % "/src") project-paths)]
    (-> opts
        (assoc :command-args (concat ["clj-kondo" "--lint" "build.clj"] src-paths))
        checked-process)))

(defn outdated
  "Check all clj-otel-* libraries, example applications and tutorials for
  outdated dependencies using antq. Dependencies on clj-otel-* libraries are
  not checked, as they are not available until after deployment."
  [opts]
  (-> opts
      (assoc :main-opts
             ["--directory" (str/join ":" project-paths)
              "--skip" "pom"
              "--exclude" (str/join ":" group-artifact-ids)])
      (cb/run-task [:antq])))

(defn fmt
  "Apply formatting to all *.clj and *.edn source files using zprint. Assumes
  a working installation of `zprint` executable binary."
  [opts]
  (let [project-files (mapcat #(globs % "src/**.clj" "*.edn" "resources/**.edn") project-paths)
        other-files   (globs "." "*.clj" "*.edn" ".clj-kondo/**.edn" "doc/**.edn")
        files         (concat project-files other-files)
        config-url    (-> ".zprint.edn"
                          io/file
                          io/as-url
                          str)]
    (-> opts
        (assoc :command-args (concat ["zprint" "--url-only" config-url "-fsw"] files))
        checked-process)))
