(ns vessel.misc-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer :all]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [vessel.misc :as misc]
            [vessel.test-helpers :refer [ensure-clean-test-dir]])
  (:import com.google.cloud.tools.jib.api.AbsoluteUnixPath
           [java.io StringReader StringWriter]
           java.nio.file.Path
           [java.time Duration Instant]
           java.util.function.Consumer))

(use-fixtures :once (ensure-clean-test-dir))

(def kv-gen (gen/tuple gen/keyword (gen/such-that (complement nil?) gen/any)))

(defspec assoc-some-spec-test
  {:num-tests 25}
  (prop/for-all [kvs (gen/fmap (partial mapcat identity)
                               (gen/not-empty (gen/vector kv-gen)))]
                (testing "assoc-some behaves like assoc for non-nil values"
                  (is (= (apply assoc {} kvs)
                         (apply  misc/assoc-some {} kvs))))))

(deftest assoc-some-test
  (is (= {:a 1}
         (misc/assoc-some {}
                          :a 1 :b nil))))

(deftest string->java-path-test
  (is (instance? Path (misc/string->java-path "/"))))

(deftest string->absolute-unix-path-test
  (is (instance? AbsoluteUnixPath (misc/string->absolute-unix-path "/"))))

(deftest java-consumer-test
  (is (instance? Consumer
                 (misc/java-consumer identity)))

  (is (= "Hello world!"
         (with-out-str
           (.. (misc/java-consumer #(printf %))
               (accept "Hello world!"))))))

(deftest now-test
  (is (instance? Instant (misc/now))))

(deftest duration-between-test
  (is (instance? Duration (misc/duration-between (misc/now)
                                                 (misc/now)))))

(deftest duration->string-test
  (are [duration result] (= result (misc/duration->string duration))
    (Duration/ZERO)           "0 milliseconds"
    (Duration/ofMillis 1)     "1 millisecond"
    (Duration/ofMillis 256)   "256 milliseconds"
    (Duration/ofMillis 1000)  "1 second"
    (Duration/ofMillis 6537)  "6.54 seconds"
    (Duration/ofMinutes 1)    "1 minute"
    (Duration/ofMillis 63885) "1.06 minutes"
    (Duration/ofMinutes 4)    "4 minutes"))

(deftest with-stderr-test
  (let [writer (StringWriter.)]
    (binding [*err* writer]
      (misc/with-stderr
        (print "Error!"))
      (is (= "Error!"
             (str writer))))))

(deftest log*-test
  (testing "prints or omits the message depending on the value bound to
  *verbose-logs* and the supplied log level"
    (are [verbose? stream level result]
         (= result
            (let [writer (java.io.StringWriter.)]
              (binding [misc/*verbose-logs* verbose?
                        stream writer]
                (misc/log* level "me" "Hello!")
                (str writer))))
      true  *out* :info  "INFO [me] Hello!\n"
      true  *out* "info" "INFO [me] Hello!\n"
      true  *out* :debug "DEBUG [me] Hello!\n"
      true  *err* :error "ERROR [me] Hello!\n"
      true  *err* :fatal "FATAL [me] Hello!\n"
      false *out* :info  "Hello!\n"
      false *err* :error "Hello!\n"
      false *err* :fatal "Hello!\n"
      false *out* :debug ""))

  (testing "supports formatting through clojure.core/format"
    (is (= "INFO [me] Hello John Doe!\n"
           (with-out-str
             (binding [misc/*verbose-logs* true]
               (misc/log* :info "me" "Hello %s!" "John Doe")))))))

(deftest log-test
  (testing "shows the log message displaying the current namespace as the
  emitter"
    (is (= "INFO [vessel.misc-test] Hello John Doe!\n"
           (with-out-str
             (binding [misc/*verbose-logs* true]
               (misc/log :info "Hello %s!" "John Doe")))))))

(def cwd (io/file (.getCanonicalPath (io/file "."))))

(deftest filter-files-test
  (is (every? #(.isFile %)
              (misc/filter-files (file-seq cwd)))))

(deftest home-dir-test
  (is (true? (misc/file-exists? (misc/home-dir)))))

(deftest make-dir-test
  (let [dir (misc/make-dir (io/file "target") "tests" "misc-test" "dir1" "dir2")]
    (is (true? (misc/file-exists? dir)))))

(deftest make-empty-dir-test
  (testing "creates a new directory"
    (let [dir (misc/make-empty-dir (io/file "target") "tests" "misc-test" "dir3" "dir4")]
      (is (true? (misc/file-exists? dir)))))

  (testing "when the directory in question already exists, guarantees that the
  returned directory is empty"
    (let [old-dir (misc/make-empty-dir (io/file "target") "tests" "misc-test" "dir5")
          _       (spit (io/file old-dir "file.txt") "Lorem Ipsum")
          new-dir (misc/make-empty-dir old-dir)]
      (is (true? (misc/file-exists? new-dir)))
      (is (empty? (.listFiles new-dir))))))

(deftest relativize-test
  (is (= (io/file "deps.edn")
         (misc/relativize (io/file cwd "deps.edn") cwd))))

(deftest read-edn-test
  (is (= {:greeting "Hello!"}
         (misc/read-edn (StringReader. "{:greeting \"Hello!\"}")))))

(deftest read-json-test
  (is (= {:greeting "Hello!"}
         (misc/read-json (StringReader. "{\"greeting\" : \"Hello!\"}")))))

(deftest sha-256-test
  (is (= "d2cf1a50c1a07db39d8397d4815da14aa7c7230775bb3c94ea62c9855cf9488d"
         (misc/sha-256 {:image
                        {:name     "my-app"
                         :registry "docker.io"
                         :version  "v1"}}))))
