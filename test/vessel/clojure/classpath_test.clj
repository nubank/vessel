(ns vessel.clojure.classpath-test
  (:require [clojure.java.io :as io]
            [matcher-combinators.matchers :as m]
            [matcher-combinators.test :refer [match?]]
            [mockfn.macros :refer [providing]]
            [vessel.clojure.classpath :as classpath]))

(deftest read-leiningen-project-test
  (testing "reads a Leiningen project.clj and returns the relevant information"
    (providing [(slurp any?)
                "(defproject my-app \"1.0.0\"
   :description  \"Fix me\"
                                                          :repositories [[\"central\"  {:url \"https://repo1.maven.org/maven2/\" :snapshots false}]]
                                                          :dependencies [[org.clojure/clojure  \"1.10.1\"]])"]
               (is (match? '{:repositories [["central"  {:url "https://repo1.maven.org/maven2/" :snapshots false}]]
                             :dependencies [[org.clojure/clojure  "1.10.1"]]}
                           (classpath/read-leiningen-project (io/file "project.clj"))))))

  (testing "reads a project.clj containing multiple forms"
    (providing [(slurp any?)
                "(def x 1)
(defproject my-app \"1.0.0\"
   :description  \"Fix me\"
                                                          :repositories [[\"central\"  {:url \"https://repo1.maven.org/maven2/\" :snapshots false}]]
                                                          :dependencies [[org.clojure/clojure  \"1.10.1\"]])"]
               (is (match? '{:repositories [["central"  {:url "https://repo1.maven.org/maven2/" :snapshots false}]]
                             :dependencies [[org.clojure/clojure  "1.10.1"]]}
                           (classpath/read-leiningen-project (io/file "project.clj")))))))

(deftest canonicalize-s3-urls-test
  (testing "replaces occurrences of s3p:// by s3://. Other URLs remain unchanged"
    (is (= {"central" {:url "https://repo1.maven.org/maven2/"}
            "s3-1"    {:url "s3://bucket/key"}
            "s3-2"    {:url "s3://bucket/key"}}
           (classpath/canonicalize-s3-urls {"central" {:url "https://repo1.maven.org/maven2/"}
                                            "s3-1"    {:url "s3://bucket/key"}
                                            "s3-2"    {:url "s3p://bucket/key"}})))))

(deftest leiningen-project->deps-map-test
  (let [deps-map (classpath/leiningen-project->deps-map '{:description  "Fix me"
                                                          :repositories [["central"  {:url "https://repo1.maven.org/maven2/" :snapshots false}]]
                                                          :dependencies [[org.clojure/clojure  "1.10.1"]
                                                                         [org.clojure/core.async "1.0.567" :exclusions [org.clojure/clojure]]]})]

    (is (match? {:mvn/repos {"central" {:url "https://repo1.maven.org/maven2/"}}}
                deps-map)
        "converts :repositories to :mvn/repos")

    (is (match? '{:deps
                  {org.clojure/clojure    {:mvn/version "1.10.1"}
                   org.clojure/core.async {:mvn/version "1.0.567" :exclusions [org.clojure/clojure]}}}
                deps-map)
        "converts :dependencies to :deps")))

(def project (io/file "test/resources/my-app"))

(deftest assemble-test
  (testing "returns all expected files"
    (is (match? (m/in-any-order ["data.json-0.2.6.jar"
                                 "clojure-1.4.0.jar"
                                 "core.specs.alpha-0.2.44.jar"
                                 "spec.alpha-0.2.176.jar"
                                 "jetty-http-9.4.25.v20191220.jar"
                                 "jetty-util-9.4.25.v20191220.jar"
                                 "jetty-io-9.4.25.v20191220.jar"
                                 "clojure-1.10.1.jar"
                                 "javax.servlet-api-3.1.0.jar"
                                 "jetty-server-9.4.25.v20191220.jar"])
                (map #(.getName %) (classpath/assemble {:clojure/tool :tools.deps :project/file (io/file project "deps.edn")})))))

  (testing "returns the same set of files for Leiningen and tools.deps"
    (is (match? (m/in-any-order  (classpath/assemble {:clojure/tool :leiningen :project/file (io/file project "project.clj")}))
                (classpath/assemble {:clojure/tool :tools.deps :project/file (io/file project "deps.edn")})))))
