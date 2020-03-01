(ns vessel.builder-test
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.test :refer :all]
            [matcher-combinators.clj-test]
            [matcher-combinators.matchers :as m]
            [matcher-combinators.test :refer [match?]]
            [vessel.builder :as builder]
            [vessel.misc :as misc]
            [vessel.test-helpers :refer [ensure-clean-test-dir]])
  (:import java.io.File))

(use-fixtures :once (ensure-clean-test-dir))

(deftest get-class-file-source-test
  (let [namespaces {'clj-jwt.base64 (io/file "clj-jwt.jar")
                    'clj-tuple (io/file "clj-tuple.jar")
                    'zookeeper (io/file "zookeeper-clj.jar")}]

    (testing "given a class file, returns its source"
      (are [file-path src] (= (io/file src)
                              (builder/get-class-file-source namespaces (io/file file-path)))
        "clj_jwt/base64$decode.class" "clj-jwt.jar"
        "clj_jwt/base64/ByteArrayInput.class" "clj-jwt.jar"
        "clj_tuple$fn__18034.class" "clj-tuple.jar"
        "zookeeper$host_acl.class" "zookeeper-clj.jar"
        "zookeeper$set_data$fn__54225.class" "zookeeper-clj.jar"
        "zookeeper__init.class" "zookeeper-clj.jar"))

    (testing "throws an exception when the source can't be found"
      (is (thrown? IllegalStateException
                   (builder/get-class-file-source namespace (io/file "clojure/zip$zipper.class")))))))

(deftest copy-files-test
  (let [src (io/file "test/resources")
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
              (io/file target "classes/lib3/resource3.edn")  (io/file src "lib3/lib3.jar")
              (io/file target "classes/META-INF/MANIFEST.MF")  (io/file src "lib3/lib3.jar")
              (io/file target "classes/data_readers.clj")  (io/file src "lib2")
              (io/file target "classes/data_readers.cljc")  (io/file src "lib4")
              (io/file target "classes/resource1.edn")  (io/file src "lib1")}
             output)))

    (testing "multiple data-readers (either data_readers.clj or
    data_readers.cljc files) found at the root of the classpath are merged into
    their respective files"
      (is (= {'lib1/url 'lib1.url/string->url
              'lib2/time 'lib2.time/string->local-date-time}
             (misc/read-edn (io/file target "classes/data_readers.clj"))))
      (is (= {'lib3/date 'lib3.date/string->local-date
              'lib4/usd 'lib4.money/bigdec->money}
             (misc/read-edn (io/file target "classes/data_readers.cljc")))))))

(defn get-file-names
  [^File dir]
  (map #(.getName %) (.listFiles dir)))

(deftest build-app-test
  (let [src (io/file "test/resources/my-app")
        target (io/file "target/tests/builder-test/build-app-test")
        classpath-files (set (map io/file (string/split (slurp (io/file src "classpath.txt")) #":")))
        options {:classpath-files classpath-files
                 :resource-paths #{(io/file src "resources")}
                 :target-dir target}
        output (builder/build-app (assoc options                                    :main-class 'my-app.server))]
    (testing "the classes directory has the expected files and directories"
      (is (match? (m/in-any-order ["clojure"
                                   "META-INF"
                                   "my_app"
                                   "resource1.edn"])
                  (get-file-names (io/file target "WEB-INF/classes")))))

    (testing "the lib directory has some known files"
      (is (match? (m/prefix [#"^core\.specs.*\.jar$"
                             #"^javax\.servlet-api.*\.jar$"
                             #"^jetty-http.*\.jar$"])
                  (sort (get-file-names (io/file target "WEB-INF/lib"))))))

    (testing "the output data contains a map in the :app/classes key whose keys
      match the existing files at the classes directory"
      (is (= (set (keys (:app/classes output)))
             (set (misc/filter-files (file-seq (io/file target "WEB-INF/classes")))))))

    (testing "the output data contains a :app/lib key whose values match the
    existing files at the lib directory"
      (is (= (set (:app/lib output))
             (set (misc/filter-files (file-seq (io/file target "WEB-INF/lib")))))))

    (testing "throws an exception describing the underwing compilation error"
      (is (thrown-match? clojure.lang.ExceptionInfo
                         #:vessel.error{:category :vessel/compilation-error}
                         (builder/build-app (assoc options :main-class 'my-app.compilation-error)))))))
