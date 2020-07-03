(ns vessel.image
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [vessel.misc :as misc])
  (:import java.io.File))

(defn- image-layers-for-extra-paths
  "Given a set of extra-paths (maps containing the keys :source, :target
  and :churn), returns a sequence of layers by grouping files
  according to their churn."
  [{:keys [extra-paths preserve-file-permissions?]}]
  (letfn [(layer-entry [{:keys [source target]}]
            (misc/assoc-some #:layer.entry{:source            (.getPath source)
                                           :target            (.getPath target)
                                           :modification-time (misc/last-modified-time source)}
                             :layer.entry/file-permissions (when preserve-file-permissions?
                                                             (misc/posix-file-permissions source))))]
    (->> extra-paths
         (group-by :churn)
         (map (fn [[churn files]]
                #:image.layer{:entries (map layer-entry files)
                              :churn   churn}))
         (sort-by :image.layer/churn)
         (map-indexed (fn [index layer]
                        (assoc layer :image.layer/name (str "extra-files-" (inc index))))))))

(defn- concat-with-extra-paths
  [{:keys [extra-paths] :as options} layers]
  (if (seq extra-paths)
    (into (image-layers-for-extra-paths options) layers)
    layers))

(defn internal-dep?
  "Is the file in question an internal dependency?"
  [^File file {:keys [internal-deps-re]}]
  (boolean
   (when internal-deps-re
     (some #(re-find % (.getPath file))
           internal-deps-re))))

(defn- subpath?
  "Returns true if this is a subpath of that."
  [^File this ^File that]
  (string/includes? (.getPath that) (.getPath this)))

(defn resource?
  "Is the file in question a resource file?"
  [^File file {:keys [resource-paths]}]
  (boolean
   (some #(subpath? % file) resource-paths)))

(defn source-file?
  "Is the file in question a source file?"
  [^File file {:keys [source-paths]}]
  (boolean
   (some #(subpath? % file) source-paths)))

(def ^:private classifiers
  "Map of classifiers for files that will be part of each image layer.

  Keys are layer names and values are a tuple of [predicate
  churn]. The churn determines whether the layer will be topmost or
  undermost positioned at the resulting image."
  {"external-deps" [(constantly false) 1] ;; default layer
   "internal-deps" [internal-dep? 3]
   "resources"     [resource? 5]
   "sources"       [source-file? 7]})

(defn- ^Boolean apply-classifier-predicate
  "Applies the predicate on the file or map entry (whose value is a
  java.io.File object to be classified)."
  [pred file-or-map-entry options]
  (cond
    (instance? File file-or-map-entry) (pred file-or-map-entry options)
    (map-entry? file-or-map-entry)     (pred (val file-or-map-entry) options)))

(defn- classify
  "Given a java.io.File or a map entry whose value is a java.io.File,
  classifies it according to heuristics extracted from the supplied
  options."
  [file-or-map-entry options]
  (or (some (fn [[layer-name [pred]]]
              (when (apply-classifier-predicate pred file-or-map-entry options)
                layer-name))
            classifiers)
      "external-deps"))

(defn- image-layer
  "Creates an image layer map from the supplied arguments."
  [[layer-name files] {:keys [app-root preserve-file-permissions? target-dir]}]
  #:image.layer{:name    layer-name
                :entries (map (fn [file-or-map-entry]
                                (let [^File file (if (map-entry? file-or-map-entry)
                                                   (key file-or-map-entry)
                                                   file-or-map-entry)]
                                  (misc/assoc-some #:layer.entry{:source            (.getPath file)
                                                                 :target            (.getPath (io/file app-root (misc/relativize file target-dir)))
                                                                 :modification-time (misc/last-modified-time file)}
                                                   :layer.entry/file-permissions (when preserve-file-permissions?
                                                                                   (misc/posix-file-permissions file)))))
                              files)
                :churn   (second (get classifiers layer-name))})

(defn- layer-comparator
  "Compares two image layers."
  [this that]
  (compare (get this :image.layer/churn)
           (get that :image.layer/churn)))

(defn- organize-image-layers
  "Takes a sequence of java.io.File objects or map entries (whose values
  are java.io.File objects) and organize them into image layers
  according to known heuristics."
  [files options]
  (->> files
       (group-by #(classify % options))
       (map #(image-layer % options))
       (concat-with-extra-paths options)
       (sort layer-comparator)))

(defn- image-reference
  "Turns image information read from a manifest into an image reference
  map. "
  [{:keys [registry repository tag]}]
  #:image{:registry   registry
          :repository repository
          :tag        tag})

(defn render-image-spec
  [{:app/keys [classes lib]} {:keys [manifest user ^File tarball] :as options}]
  (let [files (into (vec classes) lib)]
    #:image{:from     (image-reference (get-in manifest [:base-image :image]))
            :name     (image-reference (get manifest :image))
            :user     user
            :layers   (organize-image-layers files options)
            :tar-path (.getPath tarball)}))
