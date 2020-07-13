(ns vessel.sh
  "Shell with support for streaming stdout and stderr."
  (:require [clojure.java.io :as io]
            [clojure.string :as string]))

(def ^:const encoding
  "Shell encoding."
  "UTF-8")

(def ^:const working-dir
  "Directory where the shell will be started at."
  ".")

(defn- read-from-stream
  "Reads lines from the stream and sent them to the supplied output function as
  they become available."
  [process-stream output-fn]
  (with-open [stream (io/reader process-stream)]
    (loop []
      (when-let [line (.readLine stream)]
        (output-fn line)
        (recur)))))

(defn- env-vars-map->string-array
  "Takes a map of environment variables and turns it into an array of strings in
  the form var-name=var-value."
  [env-vars]
  (->> env-vars
       (reduce-kv #(conj %1 (str %2 "=" %3)) [])
       (into-array String)))

(defn- env-vars
  "Returns a map of environment variables."
  []
  (into {} (System/getenv)))

(defn exec
  [cmd & args]
  (let [args                                                        (into-array String (cons cmd args))
        process                                                     (.. Runtime getRuntime
                                                                        (exec args (env-vars-map->string-array (env-vars)) (io/file working-dir)))
        stdout                                                      (future (read-from-stream (.getInputStream process) println))
        stderr                                                      (future (read-from-stream (.getErrorStream process) #(binding [*err* *out*]
                                                                                                                           (println %))))
        exit-code                                                   (.waitFor process)]
    @stdout
    @stderr
    {:args args
     :exit-code exit-code}))

(defn- java-cmd
  "Returns a vector containing the arguments to spawn a Java sub-process."
  [classpath-files]
  [(or (System/getProperty "vessel.sh.java.cmd") "java")
   "-classpath" (string/join ":" (map str classpath-files))])

(defn clojure
  "Calls clojure.main with the supplied classpath and arguments. Throws an
  exception if the sub-process exits with an error."
  [classpath-files & args]
  (let [{:keys [exit-code] :as result}
        (apply exec (concat (java-cmd classpath-files)
                            ["clojure.main"]
                            args))]
    (when-not (zero? exit-code)
      (throw (ex-info (str "Sub-process exited with code " exit-code)
                      result)))))
