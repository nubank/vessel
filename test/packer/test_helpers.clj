(ns packer.test-helpers
  (:require [clojure.java.io :as io]
            [clojure.string :as string])
  (:import java.io.File))

(defn cleanup-dir
  [^File dir]
  (->> (io/file "target")
       file-seq
       reverse
       (run! (fn [file]
               (.delete file))))
  (io/make-parents dir)
  (.mkdir dir))

(defmacro ensure-clean-test-dir
  "Expands to a fixture function that creates a clean directory under target/tests/<namespace>.

  Therefore, by caling (use-fixture :each (ensure-clean-dir)) in a
  namespace named my-feature-test, will create a fresh directory named
  target/tests/my-feature-test before each test in this namespace."
  []
  `(fn [test#]
     (let [dir# (io/file "target" "tests" ~(last (string/split (name (ns-name *ns*)) #"\.")))]
       (cleanup-dir dir#)
       (test#))))
