(ns vessel.builder-test
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.test :refer :all]
            [matcher-combinators.matchers :as m]
            [matcher-combinators.test :refer [match?]]
            [vessel.builder :as builder]
            [vessel.misc :as misc]
            [vessel.sh :as sh]
            [babashka.fs :as fs]
            [vessel.test-helpers :refer [classpath ensure-clean-test-dir]])
  (:import java.io.File
           (clojure.lang ExceptionInfo)))

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
        "zookeeper__init.class"               "zookeeper-clj.jar"))))

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
              ;; resource1 is merged, last read file is in lib2
              (io/file target "classes/resource1.edn")        (io/file src "lib2")}
             output)))

    (testing "edn files are merged"
      ;; This is the deep merge of the two copies of resource1:
      (is (= {:list     [1 2 3 4 4 5 6]
              :map      {:a 2
                         :b 3
                         :c 4}
              :set      #{1 2 3}
              :old-key  4
              :new-key  4
              :same-key :new-value}
             (-> (io/file target "classes/resource1.edn")
                 misc/read-edn))))

    (testing "multiple data-readers (either data_readers.clj or
    data_readers.cljc files) found at the root of the classpath are merged into
    their respective files"
      (is (= {'lib1/url  'lib1.url/string->url
              'lib2/time 'lib2.time/string->local-date-time}
             (misc/read-edn (io/file target "classes/data_readers.clj"))))
      (is (= {'lib3/date 'lib3.date/string->local-date
              'lib4/usd  'lib4.money/bigdec->money}
             (misc/read-edn (io/file target "classes/data_readers.cljc")))))

    (testing "preserve timestamps when copying files"
      ;; This file is simply copied:
      (is (= (.lastModified (io/file src "lib2/libs2/resource2.edn"))
             (.lastModified (io/file target "classes/lib2/resource2.edn"))))
      ;; resource1 is merged, last version in lib2
      (is (= (.lastModified (io/file src "lib2/resource1.edn"))
             (.lastModified (io/file target "classes/resource1.edn")))))))

(deftest merge-with-reader-macros
  (let [src    (io/file "test/resources")
        target (io/file "target/tests/builder-test/reader-macros")
        _      (builder/copy-files #{(io/file src "lib5")
                                     (io/file src "lib6")}
                                   target)]

    (testing "edn files with reader macros are merged"
      (is (= (slurp (io/file target "classes" "with-reader-macros.edn"))
             "{:k1 :override-k1, :k2 #unknown/macro :v2, :k3 #weird/macro :v3}")))))

(defn get-file-names
  [^File dir]
  (map #(.getName %) (.listFiles dir)))

(deftest build-app-test
  (let [project-dir     (io/file "test/resources/my-app")
        target          (io/file "target/tests/builder-test/build-app-test")
        classpath-files (map io/file (string/split (classpath project-dir) #":"))
        options         {:classpath-files classpath-files
                         :source-paths    #{(io/file project-dir "src")}
                         :resource-paths  #{(io/file project-dir "resources")}
                         :target-dir      target}
        output          (builder/build-app (assoc options :main-class 'my-app.server))]
    (testing "the classes directory has the expected files and directories"
      (is (match? (m/in-any-order ["clojure"
                                   "META-INF"
                                   "my_app"
                                   "resource1.edn"])
                  (get-file-names (io/file target "WEB-INF/classes")))))

    (testing "the lib directory has the expected files"
      (is (match? (m/in-any-order ["core.specs.alpha-0.2.44.jar"
                                   "javax.servlet-api-3.1.0.jar"
                                   "jetty-http-9.4.25.v20191220.jar"
                                   "jetty-io-9.4.25.v20191220.jar"
                                   "jetty-server-9.4.25.v20191220.jar"
                                   "jetty-util-9.4.25.v20191220.jar"
                                   "spec.alpha-0.2.176.jar"])
                  (get-file-names (io/file target "WEB-INF/lib")))))

    (testing "the output data contains a map in the :app/classes key whose keys
      match the existing files at the classes directory"
      (is (= (set (keys (:app/classes output)))
             (set (misc/filter-files (file-seq (io/file target "WEB-INF/classes")))))))

    (testing "the output data contains a :app/lib key whose values match the
    existing files at the lib directory"
      (is (= (set (:app/lib output))
             (set (misc/filter-files (file-seq (io/file target "WEB-INF/lib")))))))

    ;; TODO: uncomment after fixing https://github.com/nubank/vessel/issues/7.
    #_(testing "throws an exception describing the underwing compilation error"
        (is (thrown-match? clojure.lang.ExceptionInfo
                           #:vessel.error{:category :vessel/compilation-error}
                           (builder/build-app (assoc options :main-class 'my-app.compilation-error)))))))

(deftest use-provided-compiler-options-when-building-app-test
  (let [project-dir     (io/file "test/resources/my-app")
        target          (io/file "target/tests/builder-test/build-app-test")
        classpath-files (map io/file (string/split (classpath project-dir) #":"))
        options         {:classpath-files  classpath-files
                         :resource-paths   #{(io/file project-dir "src")}
                         :source-paths     #{(io/file project-dir "resources")}
                         :target-dir       target
                         :main-class       'my-app.server
                         :compiler-options {:direct-linking true
                                            :testing?       true}}
        sh-args         (atom [])]
    (with-redefs [sh/javac   (constantly nil)
                  sh/clojure #(reset! sh-args [%1 %2 %3])]
      (builder/build-app options))

    (is (re-matches #".*clojure\.core/\*compiler-options\* \(clojure\.core/merge clojure\.core/\*compiler-options\* \{:direct-linking true, :testing\? true\}\).*" (last @sh-args)))))

(deftest reports-errors-when-merging-from-dir
  (let [src    (io/file "test/resources")
        badlib (io/file src "badlib")
        target (io/file "target/tests/build-test/merge-errors")
        e      (is (thrown? ExceptionInfo
                            (builder/copy-files #{badlib} target)))]
    (when e
      (is (= "Unable to read test/resources/badlib/bad-input.edn: Map literal must contain an even number of forms" (ex-message e)))
      (is (match? {:classpath-root badlib
                   :input-source   "test/resources/badlib/bad-input.edn"
                   :target-file    (m/via str (str target "/classes/bad-input.edn"))}
                  (ex-data e))))))

(deftest reports-errors-when-merging-from-jar
  (let [src        (io/file "test/resources")
        badlib     (io/file src "badlib")
        badlib-jar (io/file "target/badlib.jar")
        _          (fs/zip badlib-jar badlib {:root "test/resources/badlib"})
        target     (io/file "target/tests/build-test/merge-errors")
        e          (is (thrown? ExceptionInfo
                                (builder/copy-files #{badlib-jar} target)))]
    (when e
      (is (= "Unable to read target/badlib.jar#bad-input.edn: Map literal must contain an even number of forms" (ex-message e)))
      (is (match? {:classpath-root (m/via str "target/badlib.jar")
                   :input-source   "target/badlib.jar#bad-input.edn"
                   :target-file    (m/via str (str target "/classes/bad-input.edn"))}
                  (ex-data e))))))
