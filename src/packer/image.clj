(ns packer.image
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
  [^File file {:keys [known-source-files]}]
  {:pre [known-source-files]}
  (some #(string/ends-with? (.getName file) %)
        known-source-files))

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
  [[layer-name files] {:keys [app-root]}]
  {:pre [app-root]}
  #:image.layer{:name layer-name
                :source (map #(.getPath %) files)
                :target (web-inf-classes app-root)})

(defn- layer-comparator
  [this that]
  (let [sorting-score-of #(last (get classifiers %))]
    (compare (sorting-score-of this)
             (sorting-score-of that))))

(defn create-image-layers
  [{:keys [files] :as options}]
  {:pre [files]}
  (->> files
       (group-by #(classify % options))
       (map #(image-layer % options))
       (sort layer-comparator)))

(defn- image-reference
  "Turns image information read from a manifest into an image reference
  map. "
  [{:keys [registry name version]}]
  #:image{:registry registry
          :repository name
          :tag version})

(defn describe-image
  [manifest options]
  #:image{:from (image-reference (manifest :base-image))
          :name (image-reference (manifest :image))
          :layers (create-image-layers options)
          :tar-path (.getPath (options :output))})
