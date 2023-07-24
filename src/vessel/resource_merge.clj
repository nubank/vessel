(ns vessel.resource-merge
  "Support for merging duplicated resources on the classpath."
  (:require [vessel.misc :as misc])
  (:import (java.io File InputStream)))

;; A rule is a map:
;; :match-fn (fn [File]) -> boolean (File is the output file)
;; :read-fn (fn [InputStream]) -> value
;; :merge-fn (fn [old-value, new-value]) -> merged-value
;; :write-fn (fn [File, value]) -> nil (but writes the merged value)


(def base-rules
  [{:match-fn #(re-find #"/data_readers.cljc?$" (.getPath ^File %))
    :read-fn  misc/read-edn
    :merge-fn merge
    :write-fn (fn [path value]
                (spit path (pr-str value)))}])

(defn- apply-rule
  [^File classpath-root ^InputStream src ^File target-file last-modified rule merged-paths]
  (let [{:keys [read-fn merge-fn]} rule
        new-value (read-fn src)]
    (if (contains? merged-paths target-file)
      (update merged-paths target-file
              #(-> %
                   (update :value merge-fn new-value)
                   (assoc :classpath-root classpath-root
                          :last-modified last-modified)))
      (assoc merged-paths target-file {:rule           rule
                                       :classpath-root classpath-root
                                       :last-modified  last-modified
                                       :value          new-value}))))

(defn execute-merge-rules
  "Evaluates the inputs against the provided rules.  On match, return a tuple
  of [true merged-paths], otherwise returns [false merged-paths]"
  [^File classpath-root ^InputStream src ^File target-file last-modified merge-rules merged-paths]
  (if-let [matched-rule (some (fn [rule]
                                (when ((:match-fn rule) target-file)
                                  rule))
                              merge-rules)]
    [true (apply-rule classpath-root src target-file last-modified matched-rule merged-paths)]
    [false merged-paths]))

(defn write-merged-paths
  "After all other file reading and copying has completed, this function writes the merged files whose
   data has accumulated in the merged paths.

   Returns a map of output files to classpath root (the last classpath
   root identified for any file with the same target path)."
  [merged-paths]
  (reduce-kv
    (fn [result target-file merged-path]
      (let [{:keys [value last-modified rule classpath-root]} merged-path
            {:keys [write-fn]} rule]
        (write-fn target-file value)
        (misc/set-timestamp target-file last-modified)
        (assoc result target-file classpath-root)))
    {}
    merged-paths))

