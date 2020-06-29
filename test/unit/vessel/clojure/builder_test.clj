(ns vessel.clojure.builder-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer :all]
            [matcher-combinators.matchers :as m]
            [matcher-combinators.test :refer [match?]]
            [vessel.clojure.builder :as builder]
            [vessel.clojure.classpath :as classpath]
            [vessel.misc :as misc]
            [vessel.test-helpers :refer [ensure-clean-test-dir]])
  (:import java.io.File))

(use-fixtures :once (ensure-clean-test-dir))

(deftest get-class-file-source-test
  (let [namespaces {'clj-jwt.base64 (io/file "clj-jwt.jar")
                    'clj-tuple      (io/file "clj-tuple.jar")
                    'zookeeper      (io/file "zookeeper-clj.jar")}]

    (testing "given a class file, returns its source"
      (are [file-path src] (= (io/file src)
                              (builder/get-class-file-source namespaces (io/file file-path)))
        "clj_jwt/base64$decode.class"         "clj-jwt.jar"
        "clj_jwt/base64/ByteArrayInput.class" "clj-jwt.jar"
        "clj_tuple$fn__18034.class"           "clj-tuple.jar"
        "zookeeper$host_acl.class"            "zookeeper-clj.jar"
        "zookeeper$set_data$fn__54225.class"  "zookeeper-clj.jar"
        "zookeeper__init.class"               "zookeeper-clj.jar"))

    (testing "throws an exception when the source can't be found"
      (is (thrown? IllegalStateException
                   (builder/get-class-file-source namespace (io/file "clojure/zip$zipper.class")))))))

(deftest copy-files-test
  (let [src    (io/file "test/resources")
        target (io/file "target/tests/builder-test/copy-files-test")
        output (builder/copy-files #{(io/file src "lib1")
                                     (io/file src "lib2")
                                     (io/file src "lib3/lib3.jar")
                                     (io/file src "lib4")}
                                   target)]

    (testing "copies all src files (typically resources) to the target directory
    under the `classes` folder"
      (is (match? (m/in-any-order ["classes/META-INF/MANIFEST.MF"
                                   "classes/data_readers.clj"
                                   "classes/data_readers.cljc"
                                   "classes/lib2/resource2.json"
                                   "classes/lib3/resource3.edn"
                                   "classes/resource1.edn"])
                  (->> target
                       file-seq
                       misc/filter-files
                       (map (comp #(.getPath %) #(misc/relativize % target)))))))

    (testing "after copying files, returns a map from target to src files"
      (is (= {(io/file target "classes/lib2/resource2.json")  (io/file src "lib2")
              (io/file target "classes/lib3/resource3.edn")   (io/file src "lib3/lib3.jar")
              (io/file target "classes/META-INF/MANIFEST.MF") (io/file src "lib3/lib3.jar")
              (io/file target "classes/data_readers.clj")     (io/file src "lib2")
              (io/file target "classes/data_readers.cljc")    (io/file src "lib4")
              (io/file target "classes/resource1.edn")        (io/file src "lib1")}
             output)))

    (testing "multiple data-readers (either data_readers.clj or
    data_readers.cljc files) found at the root of the classpath are merged into
    their respective files"
      (is (= {'lib1/url  'lib1.url/string->url
              'lib2/time 'lib2.time/string->local-date-time}
             (misc/read-edn (io/file target "classes/data_readers.clj"))))
      (is (= {'lib3/date 'lib3.date/string->local-date
              'lib4/usd  'lib4.money/bigdec->money}
             (misc/read-edn (io/file target "classes/data_readers.cljc")))))))

(defn get-file-names
  [^File dir]
  (map #(.getName %) (.listFiles dir)))

(deftest build-application-test
  (let [project-dir  (io/file "test/resources/my-app")
        target-dir   (io/file "target/tests/builder-test/build-application-test")
        deps         (classpath/assemble-deps {:clojure/tool :tools.deps.alpha :project/descriptor (io/file project-dir "deps.edn")})
        options      {:deps           deps
                      :main-ns        'my-app.server
                      :source-paths   #{(io/file project-dir "src")}
                      :resource-paths #{(io/file project-dir "resources")}
                      :target-dir     target-dir}
        build-result (builder/build-application options)]

    (testing "the classes directory has the expected files and directories"
      (is (match? (m/in-any-order ["clojure"
                                   "META-INF"
                                   "my_app"
                                   "resource1.edn"])
                  (get-file-names (io/file target-dir "classes")))))

    (testing "the directory classes/my_app contains some of the expected files"
      (is (match? (m/embeds ["server.class"
                             "server__init.class"
                             "server$_main.class"
                             "server.clj"])
                  (get-file-names (io/file target-dir "classes/my_app")))))

    (testing "the lib directory has the expected files"
      (is (match? (m/in-any-order ["core.specs.alpha-0.2.44.jar"
                                   "javax.servlet-api-3.1.0.jar"
                                   "jetty-http-9.4.25.v20191220.jar"
                                   "jetty-io-9.4.25.v20191220.jar"
                                   "jetty-server-9.4.25.v20191220.jar"
                                   "jetty-util-9.4.25.v20191220.jar"
                                   "spec.alpha-0.2.176.jar"])
                  (get-file-names (io/file target-dir "lib")))))

    (testing "the build result data contains a map in the
      :clojure.application/classes key whose keys match the existing files at
      the classes directory"
      (is (= (set (misc/filter-files (file-seq (io/file target-dir "classes"))))
             (set (keys (:clojure.application/classes build-result))))))

    (testing "the build result data contains a map in the
      :clojure.application/lib key whose keys match the existing files at
      the lib directory"
      (is (= (set (misc/filter-files (file-seq (io/file target-dir "lib"))))
             (set (keys (:clojure.application/lib build-result))))))

    ;; TODO: uncomment after fixing https://github.com/nubank/vessel/issues/7.

    #_(testing "throws an exception describing the underwing compilation error"
        (is (thrown-match? clojure.lang.ExceptionInfo
                           #:vessel.error{:category :vessel/compilation-error}
                           (builder/build-application (assoc options :main-ns 'my-app.compilation-error)))))))
