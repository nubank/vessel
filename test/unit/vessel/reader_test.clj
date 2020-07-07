(ns vessel.reader-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer :all]
            [matcher-combinators.test :refer [match?]]
            [mockfn.macros :refer [calling providing]]
            [vessel.misc :as misc]
            [vessel.reader :as reader]
            [vessel.v1 :as v1]))

(deftest expand-variable-test
  (testing "replaces variables with values supplied in the build-time variables map"
    (are [s build-time-variables result]
         (is (= result (#'reader/expand-variable s build-time-variables)))
      "word"                   {}                                "word"         ;; just keep the input
      "{{}}"                   {}                                "{{}}"         ;; keep the input too
      "{{word}}"               {:word "hello"}                   "hello"
      "Hello {{word}}!"        {:word "world"}                   "Hello world!" ;; interpolate properly
      "{{word-1}} {{word-2}}!" {:word-1 "Hello" :word-2 "world"} "Hello world!" ;; multiple variables
      "{{word|default}}"       {}                                "default"      ;; use the default value
      "{{ word  |  default}}"  {:word "hello"}                   "hello"
      "{{ word  |  default}}"  {}                                "default"))

  (testing "throws an exception when there is no a matching value for the
  variable in the build-time-variables map"
    (is (thrown? clojure.lang.ExceptionInfo
                 (#'reader/expand-variable "{{word}}" {})))))

(def base-image "openjdk11:alpine")

(def target-image "nubank/clj-tool:v1.0.0")
(deftest expand-all-variables-test
  (testing "expands variables declared in the manifest and keeps other values
  untouched"
    (is (= #::v1{:from         base-image
                 :target       target-image
                 :app-root     "/nubank/clj-tool"
                 :main-ns      'nubank.clj-tool.executor
                 :source-paths #{"src"}}
           (#'reader/expand-all-variables #::v1{:from         "{{base-image | openjdk11:alpine}}"
                                                :target       "nubank/clj-tool:{{tag}}"
                                                :app-root     "/nubank/clj-tool"
                                                :main-ns      'nubank.clj-tool.executor
                                                :source-paths #{"src"}}
                                          {:tag "v1.0.0"}))))

  (testing "certain keys can't be expanded"
    (let [manifest #::v1 {:from           base-image
                          :target         target-image
                          :app-root       "{{app-root}}"
                          :main-ns        'nubank.clj-tool.executor
                          :source-paths   #{"{{src}}"}
                          :resource-paths #{"{{resources}}"}
                          :classifiers    {:nubank-libs "{{regex}}"}}]

      (is (= manifest
             (#'reader/expand-all-variables manifest
                                            {:app-root  "nubank/clj-tool"
                                             :src       "src"
                                             :resources "resources"
                                             :regex     ".*"}))))))

(deftest merge-defaults-test
  (testing "merges default values into the provided manifest"
    (is (= #::v1{:from           base-image
                 :target         target-image
                 :app-root       "/nubank/clj-tool"
                 :app-type       :jar
                 :source-paths   #{"src"}
                 :resource-paths #{}
                 :main-ns        'nubank.clj-tool.executor}
           (#'reader/merge-defaults #::v1{:from         base-image
                                          :target       target-image
                                          :source-paths #{"src"}
                                          :main-ns      'nubank.clj-tool.executor}))))

  (testing "do not override provided values"
    (let [manifest #::v1 {:from           base-image
                          :target         target-image
                          :app-root       "/opt/clj-tool"
                          :app-type       :jar
                          :source-paths   #{"src"}
                          :resource-paths #{"resources"}
                          :main-ns        'nubank.clj-tool.executor}]
      (is (= manifest (#'reader/merge-defaults manifest))))))

(def manifest #::v1{:from           base-image
                    :target         target-image
                    :source-paths   #{"src"}
                    :resource-paths #{"resources"}
                    :extra-paths    [{:from "scripts/clj-tool.sh"
                                      :to   "/usr/local/bin/clj-tool"}]
                    :main-ns        'nubank.clj-tool.executor
                    :classifiers    {:nubank-libs "nubank"}})

(deftest read-manifest-test
  (let [manifest-file (io/file "vessel.edn")]
    (providing [(misc/read-edn manifest-file) manifest
                (misc/file-exists? (io/file "src")) true
                (misc/file-exists? (io/file "resources")) true
                (misc/directory? (io/file "src")) true
                (misc/directory? (io/file "resources")) true
                (misc/file-exists? (io/file "scripts/clj-tool.sh")) true
                (misc/canonicalize-file (partial instance? java.io.File)) (calling #(io/file "/workspace" %))]

               (testing "returns a processed manifest map"
                 (is (match? #::v1{:id           #"^[a-f0-9]{64}$"
                                   :from         base-image
                                   :target       target-image
                                   :app-root     (io/file "/nubank/clj-tool") ;; derived from :main-ns
                                   :app-type     :jar
                                   :main-ns      'nubank.clj-tool.executor
                                   :source-paths #{(io/file "/workspace/src")}
                                   :resource-paths
                                   #{(io/file "/workspace/resources")}
                                   :extra-paths
                                   [{:from
                                     (io/file "/workspace/scripts/clj-tool.sh")
                                     :to (io/file "/usr/local/bin/clj-tool")}]
                                   :classifiers  {:nubank-libs (partial instance? java.util.regex.Pattern)}}
                             (reader/read-manifest manifest-file))))

               (testing "expands variables by using supplied build-time-variables"
                 (providing [(misc/read-edn manifest-file)
                             (assoc manifest ::v1/labels {"org.label-schema.vcs-ref" "{{git-sha}}"})]
                            (is (match? #::v1{:labels
                                              {"org.label-schema.vcs-ref" "64aa7fc"}}
                                        (reader/read-manifest manifest-file
                                                              {:git-sha "64aa7fc"})))))

               (testing "throws an exception when the manifest doesn't conform to the spec :vessel.v1/manifest"
                 (providing [(misc/read-edn (io/file "vessel.edn"))
                             #::v1{:target target-image}]
                            (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                                  #"^Invalid manifest. See further details about the violations below"
                                                  (reader/read-manifest manifest-file)))))

               (testing "the app root must be an absolute path"
                 (providing [(misc/read-edn manifest-file)
                             (assoc manifest ::v1/app-root "clj-tool")]
                            (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                                  #"Invalid path at :vessel.v1/app-root - \"clj-tool\" isn't an absolute path\."
                                                  (reader/read-manifest manifest-file)))))

               (testing "source paths must be existent directories"
                 (providing [(misc/file-exists? (io/file "src")) false]
                            (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                                  #"Invalid path at :vessel.v1/source-paths - no such file or directory: \"src\"\."
                                                  (reader/read-manifest manifest-file))))

                 (providing [(misc/directory? (io/file "src")) false]
                            (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                                  #"Invalid path at :vessel.v1/source-paths - \"src\" isn't a directory\."
                                                  (reader/read-manifest manifest-file)))))

               (testing "resource paths must be existent directories"
                 (providing [(misc/file-exists? (io/file "src")) true
                             (misc/file-exists? (io/file "resources")) false]
                            (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                                  #"Invalid path at :vessel.v1/resource-paths - no such file or directory: \"resources\"\."
                                                  (reader/read-manifest manifest-file))))

                 (providing [(misc/directory? (io/file "src")) true
                             (misc/directory? (io/file "resources")) false]
                            (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                                  #"Invalid path at :vessel.v1/resource-paths - \"resources\" isn't a directory\."
                                                  (reader/read-manifest manifest-file)))))

               (testing "the :from value of extra-paths must be an existent file"
                 (providing [(misc/file-exists? (io/file "src")) true
                             (misc/file-exists? (io/file "resources")) true
                             (misc/file-exists? (io/file "scripts/clj-tool.sh")) false]
                            (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                                  #"Invalid path at \[:vessel.v1/extra-paths :from\] - no such file or directory: \"scripts/clj-tool.sh\"\."
                                                  (reader/read-manifest manifest-file)))))

               (testing "the :to value of extra-paths must refer to an absolute path"
                 (providing [(misc/read-edn manifest-file)
                             (update-in manifest [::v1/extra-paths 0 :to] (constantly "scripts/clj-tool.sh"))]
                            (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                                  #"Invalid path at \[:vessel.v1/extra-paths :to\] - \"scripts/clj-tool.sh\" isn't an absolute path\." (reader/read-manifest manifest-file)))))

               (testing "classifiers must contain valid regexes"
                 (providing [(misc/read-edn manifest-file)
                             (update-in manifest [::v1/classifiers :nubank-libs] (constantly "*"))]
                            (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                                  #"Invalid regex \* at \[:vessel.v1/classifiers :nubank-libs\]"
                                                  (reader/read-manifest manifest-file))))))))
