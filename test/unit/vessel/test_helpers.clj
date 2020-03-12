(ns vessel.test-helpers
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as string]
            [vessel.misc :as misc])
  (:import java.io.File))

(defmacro ensure-clean-test-dir
  "Expands to a fixture function that creates a clean directory under target/tests/<namespace>.

  Therefore, by caling (use-fixture :each (ensure-clean-dir)) in a
  namespace named my-feature-test, will create a fresh directory named
  target/tests/my-feature-test before each test in this namespace."
  []
  `(fn [test#]
     (misc/make-empty-dir "target" "tests" ~(last (string/split (name (ns-name *ns*)) #"\.")))
     (test#)))

(defn ^String classpath
  "Runs the clojure -Spath command to determine the classpath of the
  project at working-dir."
  [^File working-dir]
  (let [{:keys [exit err out]}
        (shell/sh "clojure" "-Spath"
                  :dir working-dir
                  :env (into {} (System/getenv)))]
    (if (zero? exit)
      (->> (string/split out #":")
           (map (fn [path]
                  (if (.isAbsolute (io/file path))
                    path
                    (str (io/file (.getAbsoluteFile working-dir) path)))))
           (string/join ":"))
      (throw (ex-info "clojure -Spath failed"
                      {:process-output err})))))
