(ns build
  (:refer-clojure :exclude [test])
  (:require [clojure.tools.build.api :as b] ; for b/git-count-revs
            [org.corfield.build :as bb]))

(def lib 'com.github.dgr/clj-tda)
(def version "1.0.1")
#_ ; alternatively, use MAJOR.MINOR.COMMITS:
(def version (format "1.0.%s" (b/git-count-revs nil)))

(defn clean "Clean up."
  [opts]
  (bb/clean opts))

(defn test "Run the tests."
  [opts]
  (bb/run-tests opts))

(defn ci "Run the CI pipeline of tests (and build the JAR)."
  [opts]
  (-> opts
      (assoc :lib lib :version version :src-pom "template/pom.xml")
      (bb/run-tests)
      (bb/clean)
      (bb/jar)))

(defn install "Install the JAR locally."
  [opts]
  (-> opts
      (assoc :lib lib :version version)
      (bb/install)))

(defn deploy "Deploy the JAR to Clojars."
  [opts]
  (-> opts
      (assoc :lib lib :version version)
      (bb/deploy)))
