(ns vessel.clojure.builder-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer :all]
            [clojure.tools.namespace.file :as namespace.file]
            [matcher-combinators.matchers :as m]
            [matcher-combinators.test :refer [match?]]
            [vessel.clojure.builder :as builder]
            [vessel.clojure.classpath :as classpath]
            [vessel.misc :as misc]
            [vessel.test-helpers :refer [ensure-clean-test-dir]]
            [vessel.v1 :as v1])
  (:import java.io.File))

(use-fixtures :once (ensure-clean-test-dir))

(deftest get-class-file-source-test
  (let [namespaces {'clj-jwt.base64 (io/file "clj-jwt.jar")
                    'clj-tuple      (io/file "clj-tuple.jar")
                    'zookeeper      (io/file "zookeeper-clj.jar")}]

    (testing "given a class file, returns its source"
      (are [file-path src] (= (io/file src)
                              (#'builder/get-class-file-source namespaces (io/file file-path)))
        "clj_jwt/base64$decode.class"         "clj-jwt.jar"
        "clj_jwt/base64/ByteArrayInput.class" "clj-jwt.jar"
        "clj_tuple$fn__18034.class"           "clj-tuple.jar"
        "zookeeper$host_acl.class"            "zookeeper-clj.jar"
        "zookeeper$set_data$fn__54225.class"  "zookeeper-clj.jar"
        "zookeeper__init.class"               "zookeeper-clj.jar"))

    (testing "throws an exception when the source can't be found"
      (is (thrown? IllegalStateException
                   (#'builder/get-class-file-source namespace (io/file "clojure/zip$zipper.class")))))))

(deftest copy-files-test
  (let [source (io/file "test/resources/clojure-builder")
        target (io/file "target/tests/builder-test/copy-files-test")
        output (#'builder/copy-files [(io/file source "lib1")
                                      (io/file source "lib2")
                                      (io/file source "lib3/lib3.jar")
                                      (io/file source "lib4")]
                                     target
                                     #{#"\.RSA$"})]

    (testing "copies all relevant files to the target directory. Excludes
    Clojure source files (except data-readers), manifest
    files (META-INF/MANIFEST.MF) and files that match the supplied exclusion
    filters"
      (is (match? (m/in-any-order ["data_readers.clj"
                                   "data_readers.cljc"
                                   "lib2/resource2.json"
                                   "lib3/resource3.edn"
                                   "resource1.edn"])
                  (->> target
                       file-seq
                       misc/filter-files
                       (map (comp #(.getPath %) #(misc/relativize % target)))))))

    (testing "after copying files, returns a map of target to source files"
      (is (= {(io/file target "lib2/resource2.json") (io/file source "lib2")
              (io/file target "lib3/resource3.edn")  (io/file source "lib3/lib3.jar")
              (io/file target "data_readers.clj")    (io/file source "lib2")
              (io/file target "data_readers.cljc")   (io/file source "lib4")
              (io/file target "resource1.edn")       (io/file source "lib1")}
             output)))

    (testing "multiple data-readers (either data_readers.clj or
    data_readers.cljc files) found at the root of the classpath are merged into
    their respective files"
      (is (= {'lib1/url  'lib1.url/string->url
              'lib2/time 'lib2.time/string->local-date-time}
             (misc/read-edn (io/file target "data_readers.clj"))))
      (is (= {'lib3/date 'lib3.date/string->local-date
              'lib4/usd  'lib4.money/bigdec->money}
             (misc/read-edn (io/file target "data_readers.cljc")))))))

(defn get-file-names
  [^File dir]
  (map #(.getName %) (.listFiles dir)))

(deftest build-application-test
  (let [project-dir  (io/file "test/resources/my-app")
        target-dir   (misc/make-empty-dir (io/file "target/tests/builder-test/build-application-test/classes"))
        deps         (classpath/assemble-deps {:clojure/tool :tools.deps.alpha :project/descriptor (io/file project-dir "deps.edn")})
        manifest     #::v1 {:main-ns        'my-app.server
                            :source-paths   #{(io/file project-dir "src")}
                            :resource-paths #{(io/file project-dir "resources")}}
        build-result (builder/build-application manifest deps target-dir)]

    (testing "the target directory contains some of the expected directories and files"
      (is (match? (m/embeds ["clojure"
                             "javax"
                             "my_app"
                             "org"
                             "about.html"
                             "resource1.edn"])
                  (get-file-names target-dir))))

    (testing "the directory my_app contains the expected files"
      (is (match? (m/embeds ["server.class"
                             "server__init.class"
                             "server$_main.class"])
                  (get-file-names (io/file target-dir "my_app")))))

    (testing "the keys of the build-result map match all files created in the
    target directory"
      (is (= (set (misc/filter-files (file-seq target-dir)))
             (set (keys build-result)))))

    (testing "excludes certain files from the built artifact"
      (is (empty?
           (filter namespace.file/clojure-file? (misc/filter-files (file-seq target-dir))))
          "excludes Clojure source files")

      (is (not (misc/file-exists?
                (io/file target-dir "META-INF/MANIFEST.MF")))
          "excludes jar manifests"))))
