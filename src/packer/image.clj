(ns packer.image
  (:require [clojure.java.io :as io])
  (:import java.io.File))

(defn internal-dep?
  "Is the file in question an internal dependency?"
  [^File file {:keys [internal-deps-re]}]
  (when internal-deps-re
    (some #(re-find % (.getPath file))
          internal-deps-re)))

(defn- same-file-as-one-of?
  [^File file files-to-compare]
  (some #(= (.getName file)
            (.getName %))
        files-to-compare))

(defn- resource?
  [^File file {:keys [known-resources]}]
  {:pre [known-resources]}
  (same-file-as-one-of? file known-resources))

(defn- source-file?
  [^File file {:keys [known-sources]}]
  {:pre [known-sources]}
  (same-file-as-one-of? file known-sources))

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
  [this that]
  (let [sorting-score-of #(last (get classifiers (:image.layer/name %)))]
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

(defn render-build-plan
  [{:keys [manifest tarball] :as options}]
  #:image{:from (image-reference (manifest :base-image))
          :name (image-reference (manifest :image))
          :layers (create-image-layers options)
          :tar-path (.getPath tarball)})
