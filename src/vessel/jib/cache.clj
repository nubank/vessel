(ns vessel.jib.cache
  (:import com.google.cloud.tools.jib.api.buildplan.FileEntriesLayer
           com.google.cloud.tools.jib.api.DescriptorDigest
           [com.google.cloud.tools.jib.cache Cache CachedLayer]
           com.google.common.collect.ImmutableList
           java.io.File
           java.util.Optional))

(defn ^Cache get-cache
  "Takes a java.io.File representing the cache directory and returns the
  corresponding com.google.cloud.tools.jib.cache.Cache object."
  [^File cache-dir]
  (Cache/withDirectory (.toPath cache-dir)))

(defn ^CachedLayer get-cached-layer-by-file-entries-layer
  "Given a FileEntriesLayer object, returns the corresponding cached layer as a CachedLayer object.

  This function is supposed to always return a cached layer. If for
  some reason there is no a matching cached layer, it throws an
  IllegalStateException."
  [^Cache cache ^FileEntriesLayer layer]
  (let [^Optional cached-layer (.. cache (retrieve (ImmutableList/copyOf (.getEntries layer))))]
    (if (.isPresent cached-layer)
      (.get cached-layer)
      (throw (IllegalStateException. (str "Could not retrieve cached layer for layer " (.getName layer)))))))

(defn ^CachedLayer get-cached-layer-by-digest
  "Given a sha256 digest, returns the corresponding cached layer as a CachedLayer object.

  This function is supposed to always return a cached layer. If for
  some reason there is no a matching cached layer, it throws an
  IllegalStateException."
  [^Cache cache ^String digest]
  (let [^Optional cached-layer (.. cache (retrieve (DescriptorDigest/fromDigest digest)))]
    (if (.isPresent cached-layer)
      (.get cached-layer)
      (throw (IllegalStateException. (str "Could not retrieve cached layer for digest " digest))))))
