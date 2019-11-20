(ns packer.heuristics
  (:require [clojure.string :as string])
  (:import java.io.File))

(defn- internal-dep?
  [^File file _]
  (and (.isDirectory file)
       (re-find #"common_.*$" (.getName file))))

(defn- resource?
  [^File file _]
  (and (.isFile file)
       (not (string/ends-with? (.getName file) ".class"))))

(defn- source-file?
  [^File file {:project/keys [source-files]}]
  {:pre [source-files]}
  (some #(string/ends-with? (.getName file) %)
        source-files))

(def ^:private classifiers
  {:external-deps [(constantly false) 0]
   :internal-deps [internal-dep? 1]
   :resources [resource? 2]
   :source-files [source-file? 3]})

(defn- classify
  [^File file options]
  (or (some (fn [[layer-name [pred]]]
              (when (pred file options)
                layer-name))
            classifiers)
      :external-deps))

(defn- web-inf-classes
  [relative-to]
  (str relative-to "/WEB-INF/classes"))

(defn- image-layer
  [[layer-name files] {:image.layer/keys [target-path]}]
  {:pre [target-path]}
  #:image.layer{:name layer-name
                :source (map #(.getPath %) files)
                :target (web-inf-classes target-path)})

(defn- layer-comparator
  [this that]
  (let [sorting-score-of #(last (get classifiers %))]
    (compare (sorting-score-of this)
             (sorting-score-of that))))

(defn create-image-layers
  [{:project/keys [files] :as options}]
  {:pre [files]}
  (->> files
       (group-by #(classify % options))
       (map #(image-layer % options))
       (sort layer-comparator)))
