(ns vessel.container-builder
  (:require [clojure.java.io :as io]
            [vessel.clojure.builder :as clojure.builder]
            [vessel.clojure.classpath :as clojure.classpath]
            [vessel.hashing :as hashing]
            [vessel.jib.cache :as jib.cache]
            [vessel.jib.containerizer :as jib.containerizer]
            [vessel.misc :as misc]
            [vessel.v1 :as v1])
  (:import [clojure.lang IMapEntry IPersistentMap IPersistentSet Keyword Sequential]
           java.io.File
           java.time.Instant
           java.util.regex.Pattern))

(defn- ^Sequential resolve-deps
  "Resolve project dependencies by looking for a deps.edn or a project.clj
  files, in that order, at the specified project directory. Throws an exception
  if none of them could be found at this location.

  See also: vessel.clojure.classpath/assemble-deps."
  [^File project-dir]
  (let [deps-edn (io/file project-dir "deps.edn")]
    (if (misc/file-exists? deps-edn)
      (clojure.classpath/assemble-deps {:clojure/tool :tools.deps.alpha :project/descriptor deps-edn})
      (let [project-clj (io/file project-dir "project.clj")]
        (if (misc/file-exists? project-clj)
          (clojure.classpath/assemble-deps {:clojure/tool :leiningen :project/descriptor project-clj})
          (throw (java.io.FileNotFoundException. (format "There is no deps.edn or project.clj at %s.%n You must specify a valid project directory containing one of those project descriptors." (.getPath project-dir)))))))))

(defn- ^IPersistentMap classify-deps
  "Takes a map of classifiers (keyword -> java.util.regex.Pattern) and a
  sequence of project dependencies (java.io.File objects). Groups those
  dependencies into a map whose keys are the names of the layers where each
  group of dependencies must be bundled and values are sets containing
  dependencies themselves.

  If no classifiers are given, all dependencies are be classified as
  :application-deps. If a dependency doesn't match none of the supplied
  classifiers, it's classified as :other-deps."
  [^IPersistentMap classifiers ^Sequential deps]
  (if-not (seq classifiers)
    {:application-deps (set deps)}
    (misc/map-vals set
                   (group-by (fn [^File dep]
                               (or (some (fn [[^Keyword layer-id ^Pattern pattern]]
                                           (when (re-find pattern (.getPath dep))
                                             layer-id))
                                         classifiers)
                                   :other-deps))
                             deps))))

(defn- ^IPersistentMap construct-layering-data
  "Returns a map containing relevant information to arrange application files,
  such as compiled sources, resource files and libraries, into image layers.

  The following keys are yield:

  :layers/cacheable - a sequence of ids (keywords) of layers that are eligible
  to be cached in the extended cache.

  :layers/files - a map of layer id (keywords) to paths (set of java.io.File
  instances) that belong to that layer.

  :layers/hashes - a map of layer id (keyword) to their respective hash
  (string)."
  [^IPersistentMap manifest ^Sequential deps]
  (let [{::v1/keys [classifiers source-paths resource-paths]} manifest
        classified-deps                                       (classify-deps classifiers deps)
        cacheable-layers                               (set (into (conj (keys classified-deps) :source-paths)
                                                                  (when (seq (::v1/resource-paths manifest))
                                                                    [:resource-paths])))
        files-to-be-layered                                   (into {:source-paths   source-paths
                                                                     :resource-paths resource-paths}
                                                                    classified-deps)
        layering-data                                         #:layers {:cacheable cacheable-layers
                                                                        :files            files-to-be-layered}]
    (reduce (fn [result ^Keyword layer-id]
              (assoc-in result [:layers/hashes layer-id]
                        (hashing/sha256 (get files-to-be-layered layer-id))))
            layering-data cacheable-layers)))

(defn- make-cached-layer
  "Returns a map describing a cached layer when there is a cache hit for the
  layer id in the supplied index. Returns nil otherwise."
  [^IPersistentMap index ^IPersistentMap hashes ^Keyword layer-id]
  (when-let [{:layer/keys [digest hash]} (get index layer-id)]
    (let [current-hash (get hashes layer-id)]
      (when (= hash current-hash)
        #:layer{:id                layer-id
                :origin            :extended-cache
                :cacheable true
                :hash              hash
                :digest            digest}))))

(defn- ^Sequential make-cached-layers
  "Attempts to return cached layers for files that derive direct or indirectly
  from Clojure sources.

  Cache hits occur when the following conditions are met:

  - the flag no-extended-cache is falsy (the extended cache is disabled
  otherwise).
  - There is an index file describing the relationships between sources (src
  paths, resource files and application dependencies) and layers (tarballs
  identified by their digests) stored in the Google Jib cache.
  - The current hash of a given source matches the hash of the same source in
  the index file. In other words, the source in question hasn't changed since
  the last known build.

  Returns nil when a cache miss occurs."
  [^IPersistentMap manifest ^IPersistentMap options ^IPersistentMap layering-data]
  (let [{::v1/keys [id, target]}                                              manifest
        {:keys [cache-dir, no-extended-cache]}                                                  options
        {:layers/keys [cacheable, hashes]} layering-data]
    (when-not no-extended-cache
      (when-let [index                                                                (jib.cache/retrieve-extended-cache-index cache-dir target id)]
        (keep (partial make-cached-layer index hashes)
              cacheable)))))

(defn- ^Keyword find-most-appropriate-layer
  "Returns the id of the most appropriate layer to add the supplied file."
  [^IPersistentMap files-to-be-layered ^IMapEntry file-mapping]
  (let [[^File target ^File origin] file-mapping]
    (some (fn [[^Keyword layer-id ^IPersistentSet files]]
            (when (contains? files origin)
              layer-id))
          files-to-be-layered)))

(defn- distribute-files-across-respective-layers
  [^IPersistentMap file-mappings ^IPersistentSet cached-layer-ids ^IPersistentMap files-to-lookup]
  (loop [file-mapping  (first file-mappings)
         next-mappings (next file-mappings)
         distributed-files   {}]
    (if-not file-mapping
      distributed-files
      (let [^Keyword layer-id (find-most-appropriate-layer files-to-lookup file-mapping)]
        (recur (first next-mappings)
               (next next-mappings)
               (if (contains? cached-layer-ids layer-id)
                 ;; Skip this file because it belongs to a layer that has already been cached.
                 distributed-files
                 (update distributed-files layer-id
                         (partial cons (key file-mapping)))))))))

(defn- bundle-needed-files
  ""
  [^IPersistentMap files-to-be-layered ^File classes-dir]
  (into {}
        (map (fn [[^Keyword layer-id, ^Sequential files]]
               (if (= layer-id :resource-paths)
                 [layer-id files]
                 (let [^File jar (io/file (.getParentFile classes-dir) (str (name layer-id) ".jar"))]
                   [layer-id
                    [(clojure.builder/bundle-up jar files classes-dir)]])))
             files-to-be-layered)))

(defn- ^File resolve-path-in-container
  ""
  [^IPersistentMap manifest ^Keyword layer-id ^File classes-dir ^File file-to-add]
  (let [{::v1/keys [app-root, app-type]} manifest
        root-dir (case app-type
                   :jar app-root
                   :war (io/file app-root "WEB-INF"))]
    (if (= layer-id :resource-paths)
      (io/file root-dir "classes" (misc/relativize file-to-add classes-dir))
      (io/file root-dir "lib" (misc/relativize file-to-add (.getParentFile classes-dir))))))

(defn- make-file-entries-layer
  [^IPersistentMap manifest ^IPersistentMap layering-data [^Keyword layer-id ^Sequential files-to-be-layered] ^File classes-dir]
  (let [{:layers/keys [cacheable, hashes]}                   layering-data]
    #:layer{:id layer-id
            :origin :file-entries
            :cacheable true
            :hash (get hashes layer-id)
            :entries (map (fn [^File file-to-add]
                            #:layer.entry                         {:source            (.getPath file-to-add)
                                                                   :target            (.getPath (resolve-path-in-container manifest layer-id classes-dir file-to-add))
                                                                   :modification-time (Instant/ofEpochMilli 0)})
                          files-to-be-layered)}))

(defn- make-file-entries-layers
  [^IPersistentMap manifest ^Sequential deps ^IPersistentMap layering-data ^Sequential cached-layers]
  (let [{:layers/keys [cacheable, files]} layering-data
        cached-layer-ids                        (set (map :layer/id cached-layers))]
    (when-not (= cached-layer-ids cacheable)
      (let [{::v1/keys [id]} manifest
            ^File classes-dir (misc/make-empty-dir "/tmp/vessel" id "classes")
            ^IPersistentMap file-mappings (clojure.builder/build-application manifest deps classes-dir)
            ^IPersistentMap distributed-files (distribute-files-across-respective-layers file-mappings cached-layer-ids files)
            ^IPersistentMap files-to-be-layered (bundle-needed-files distributed-files classes-dir)]
        (map #(make-file-entries-layer manifest layering-data % classes-dir)
             files-to-be-layered)))))

(defn- ^IPersistentMap make-extra-paths-layer
  "Returns a layer map representing extra paths possibly declared in the
  supplied manifest."
  [^IPersistentMap manifest]
  (let [{::v1/keys [extra-paths]} manifest]
    (when (seq extra-paths)
      #:layer{:id                :extra-paths
              :origin            :file-entries
              :cacheable false
              :entries           (map (fn [{:keys [from to preserve-permissions]}]
                                        (misc/assoc-some #:layer.entry{:source            (.getPath from)
                                                                       :target            (.getPath to)
                                                                       :modification-time (Instant/ofEpochMilli 0)}
                                                         :layer.entry/file-permissions (when preserve-permissions
                                                                                         (misc/posix-file-permissions from))))
                                      extra-paths)})))

(defn make-build-plan
  [^IPersistentMap manifest ^IPersistentMap options]
  (let [{::v1/keys [from, target]} manifest
        {:keys [project-dir]}     options
        deps                      (resolve-deps project-dir)
        layering-data             (construct-layering-data manifest deps)
        cached-layers             (make-cached-layers manifest options layering-data)
        file-entries-layers       (make-file-entries-layers manifest deps layering-data cached-layers)
        extra-paths-layer         (make-extra-paths-layer manifest)
        layers                    (into cached-layers file-entries-layers)]
    #:image {:manifest manifest
             :from   from
             :target target
             :layers (if extra-paths-layer
                       (conj layers extra-paths-layer)
                       layers)}))

(defn- build-index
  ""
  [^IPersistentMap build-plan ^IPersistentMap build-result]
  (apply merge
         (keep (fn [{:layer/keys [cacheable, digest, hash, id]}]
                 (when cacheable
                   {id #:layer {:digest (or digest
                                            (get-in build-result [:application.layers/digests id]))
                                :hash   hash}}))
               (get build-plan :image/layers))))

(defn apply-build-plan
  ""
  [^IPersistentMap build-plan ^IPersistentMap options]
  (let [{:image/keys [digest reference] :as build-result}                  (jib.containerizer/containerize build-plan options)
        index                                                              (build-index build-plan build-result)
        {:keys [cache-dir digest-path image-reference-path]} options
        {{::v1/keys [id]} :image/manifest} build-plan]
    (jib.cache/upsert-extended-cache-index cache-dir reference id index)
    (when digest-path
      (spit digest-path digest))
    (when image-reference-path
      (spit image-reference-path reference))))
