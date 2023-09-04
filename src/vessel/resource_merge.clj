(ns vessel.resource-merge
  "Support for merging duplicated resources on the classpath."
  (:require [clojure.java.io :as io]
            [vessel.misc :as misc])
  (:import (java.io File InputStream)))


(defn- write-edn
  [path value]
  (spit path (pr-str value)))

(declare ^:private deep-merge)

(defn- deep-merge
  [left right]
  (cond
    (map? left)
    (merge-with deep-merge left right)

    (sequential? left)
    (into left right)

    :else
    right))

;; A rule is a map:
;; :match-fn (fn [File]) -> boolean (File is the output file)
;; :read-fn (fn [InputStream]) -> value
;; :merge-fn (fn [old-value, new-value]) -> merged-value
;; :write-fn (fn [File, value]) -> nil (but writes the merged value)

(def base-rules
  "Basic rules:

  data_readers.clj/cljc - merged together
  *.edn - deep merged together"
  [{:match-fn #(re-find #"/data_readers.cljc?$" (.getPath ^File %))
    :read-fn  misc/read-data
    :merge-fn merge
    :write-fn write-edn}
   {:match-fn #(.endsWith ".edn" (.getPath ^File %))
    :read-fn  misc/read-edn
    :merge-fn deep-merge
    :write-fn write-edn}])

(defn new-merge-set
  "Creates a new merge set, with the provided rules (or [[base-rules]] as a default).
  Merge sets are impure: they contain a mutable atom to track files that may be merged
  if multiple copies are found."
  ([]
   (new-merge-set base-rules))
  ([rules]
   {::rules        rules
    ::*merged-paths (atom {})}))

(defn- apply-rule
  [classpath-root ^InputStream input-stream target-file last-modified rule merge-set]
  (let [{::keys [*merged-paths]} merge-set
        {:keys [read-fn merge-fn]} rule
        new-value (read-fn input-stream)]
    (.close input-stream)
    (swap! *merged-paths
           (fn [merged-paths]
             (if (contains? merged-paths target-file)
               (update merged-paths target-file
                       #(-> %
                            (update :value merge-fn new-value)
                            (assoc :classpath-root classpath-root
                                   :last-modified last-modified)))
               (assoc merged-paths target-file {:rule           rule
                                                :classpath-root classpath-root
                                                :last-modified  last-modified
                                                :value          new-value}))))))

(defn execute-merge-rules
  "Evaluates the inputs against the rules in the provided merge set.  Returns true
  if the file is subject to a merge rule (in which case, it should not be simply copied)."
  [classpath-root input-stream target-file last-modified merge-set]
  (let [{::keys [rules]} merge-set
        matched-rule (some (fn [rule]
                             (when ((:match-fn rule) target-file)
                               rule))
                           rules)]
    (if matched-rule
      (do
        (apply-rule classpath-root input-stream target-file last-modified matched-rule merge-set)
        true)
      false)))

(defn write-merged-paths
  "After all other file reading and copying has completed, this function writes the merged files whose
   data has accumulated in the merged paths.

   Returns a map of output files to classpath root (the last classpath
   root identified for any file with the same target path)."
  [merge-set]
  (reduce-kv
    (fn [result target-file merged-path]
      (let [{:keys [value last-modified rule classpath-root]} merged-path
            {:keys [write-fn]} rule]
        (io/make-parents target-file)
        (write-fn target-file value)
        (misc/set-timestamp target-file last-modified)
        (assoc result target-file classpath-root)))
    {}
    (-> merge-set ::*merged-paths deref)))
