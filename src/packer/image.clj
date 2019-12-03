(ns packer.image
  (:require [clojure.java.io :as io])
  (:import java.io.File))

(defn internal-dep?
  "Is the file in question an internal dependency?"
  [^File file {:keys [internal-deps-re]}]
  (boolean
   (when internal-deps-re
     (some #(re-find % (.getPath file))
           internal-deps-re))))

(defn- same-file-as-one-of?
  [^File file files-to-compare]
  (boolean
   (when (seq files-to-compare)
     (some #(= (.getName file)
               (.getName %))
           files-to-compare))))

(defn resource?
  "Is the file in question a resource file?"
  [^File file {:keys [known-resources]}]
  (same-file-as-one-of? file known-resources))

(defn source-file?
  "Is the file in question a source file?"
  [^File file {:keys [known-sources]}]
  (same-file-as-one-of? file known-sources))

(def ^:private classifiers
  "Map of classifiers for files that will be part of each image layer.

  Keys are layer names and values are a tuple of [predicate
  score]. The score determines whether the layer will be topmost or
  undermost positioned at the resulting image."
  {:external-deps [(constantly false) 0]
   :internal-deps [internal-dep? 1]
   :resources [resource? 2]
   :source-files [source-file? 3]})

(defn- classify
  "Given a java.io.File, classifies it according to heuristics
  determined by the provided options."
  [^File file options]
  (or (some (fn [[layer-name [pred]]]
              (when (pred file options)
                layer-name))
            classifiers)
      :external-deps))

(defn- web-inf-classes
  ^String
  [^File relative-to]
  (.getPath (io/file relative-to "WEB-INF/classes")))

(defn- image-layer
  [[layer-name files] {:keys [^File app-root]}]
  {:pre [app-root]}
  #:image.layer{:name layer-name
                :source (map #(.getPath %) files)
                :target (web-inf-classes app-root)})

(defn- layer-comparator
  "Compares two image layers."
  [this that]
  (let [sorting-score-of #(last (get classifiers (:image.layer/name %)))]
    (compare (sorting-score-of this)
             (sorting-score-of that))))

(defn- organize-image-layers
  "Takes a sequence of java.io.File objects and organize them into image
  layers according to known heuristics."
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

(defn render-containerization-plan
  [{:keys [manifest ^File tarball] :as options}]
  #:image{:from (image-reference (manifest :base-image))
          :name (image-reference (manifest :image))
          :layers (organize-image-layers options)
          :tar-path (.getPath tarball)})
