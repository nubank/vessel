(ns vessel.jib.pusher
  "Push API built on top of Google Jib."
  (:require [clojure.core.async :as async :refer [<! <!! >!!]]
            [clojure.java.io :as io]
            [progrock.core :as progrock]
            [vessel.jib :as jib]
            [vessel.misc :as misc])
  (:import [com.google.cloud.tools.jib.api CredentialRetriever DescriptorDigest ImageReference LogEvent]
           [com.google.cloud.tools.jib.blob Blob BlobDescriptor Blobs]
           com.google.cloud.tools.jib.event.EventHandlers
           com.google.cloud.tools.jib.event.events.ProgressEvent
           com.google.cloud.tools.jib.frontend.CredentialRetrieverFactory
           com.google.cloud.tools.jib.hash.Digests
           com.google.cloud.tools.jib.http.FailoverHttpClient
           [com.google.cloud.tools.jib.image.json BuildableManifestTemplate V22ManifestTemplate]
           com.google.cloud.tools.jib.registry.RegistryClient
           com.google.cloud.tools.jib.tar.TarExtractor
           java.io.File))

(defn- show-progress
  [progress-bar]
  (progrock/print progress-bar)
  (println)
  progress-bar)

(defn progress-monitor
  [^File temp-dir]
  (let [channel    (async/chan)
        total-size (apply + (map #(.length %) (.listFiles temp-dir)))]
    (async/go-loop [progress-bar (show-progress (progrock/progress-bar total-size))]
      (let [{:progress/keys [status value]} (<! channel)]
        (if (= :done status)
          (do (show-progress (progrock/done progress-bar))
              (async/close! channel))
          (recur (show-progress (progrock/tick progress-bar value))))))
    channel))

(defn ^CredentialRetriever make-docker-config-retriever
  "Returns an instance of a CredentialRetriever for retrieving
  credentials from Docker config."
  [^ImageReference image-reference]
  (.. CredentialRetrieverFactory
      (forImage image-reference jib/log-event-handler)
      dockerConfig))

(defn- ^EventHandlers make-event-handlers
  "Returns an instance of EventHandlers containing handlers for log and
  progress events."
  []
  (.. EventHandlers builder
      (add LogEvent jib/log-event-handler)
      (add ProgressEvent jib/progress-event-handler)
      build))

(defn authenticate
  "Authenticates the client on the registry."
  [^RegistryClient client]
  (when-not (.doPushBearerAuth client)
    (.configureBasicAuth client)))

(defn ^RegistryClient make-registry-client
  "Given an image reference, returns a registry client object capable of
  pushing the image in question to the remote registry."
  [^ImageReference image-reference {:keys [allow-insecure-registries? anonymous?]
                                    :or   {allow-insecure-registries? false
                                           anonymous?                 false}}]
  (let [^Credential credential
        (when-not anonymous?
          (.. (make-docker-config-retriever image-reference) retrieve get))
        ^FailoverHttpClient http-client (FailoverHttpClient. allow-insecure-registries? (not anonymous?) jib/log-event-handler)
        ^EventHandlers event-handlers   (make-event-handlers)
        ^RegistryClient client
        (-> (RegistryClient/factory event-handlers (.getRegistry image-reference) (.getRepository image-reference) http-client)
            (cond-> (not anonymous?)
              (.setCredential credential))
            (.setUserAgentSuffix "vessel")
            (.newRegistryClient))]
    (when-not anonymous?
      (authenticate client))
    (misc/log :info "The push refers to repository [%s]" image-reference)
    client))

(defn- push-blob
  "Pushes the blob into the target registry."
  [^RegistryClient client progress-channel {:blob/keys [descriptor reader]}]
  (let [^Blob blob    (reader)
        byte-listener (misc/java-consumer (fn [^Long bytes]
                                            >!! progress-channel #:progress {:value  bytes
                                                                             :status :progress}))]
    (.pushBlob client (.getDigest descriptor)
               blob
               nil
               byte-listener)))

(defn- ^Boolean check-blob
  "Checks whether the supplied digest exists on the target registry."
  [^RegistryClient client ^DescriptorDigest digest]
  (.. client (checkBlob digest) isPresent))

(defn- ^BlobDescriptor push-layer*
  [^RegistryClient client progress-channel {:blob/keys [descriptor] :as blob-data}]
  (let [^DescriptorDigest digest (.getDigest descriptor)]
    (if (check-blob client digest)
      (do (>!! progress-channel #:progress{:value  (.getSize descriptor)
                                           :status :progress})
          (misc/log :info "%s: layer already exists on target repository" digest))
      (do (push-blob client progress-channel blob-data)
          (misc/log :info "%s: layer pushed" digest)))
    descriptor))

(defn ^BlobDescriptor push-layer
  "Pushes a layer into the target registry.

  First, checks whether the layer in question already exists in the
  repository. If so, skips the push. Otherwise, reads the layer from
  disk and transfers it to the registry.

  Returns the blob descriptor that represents the layer if the push
  succeeds or a Throwable object if the same fails."
  [^RegistryClient client progress-channel {:blob/keys [descriptor] :as blob-data}]
  (try
    (push-layer* client progress-channel blob-data)
    (catch Throwable error
      (misc/log :error "%s: push failed" (.getDigest descriptor))
      error)))

(defn ^BlobDescriptor push-container-config
  "Pushes the container configuration into the registry.

  Returns the BlobDescriptor object that represents the container
  configuration."
  [^RegistryClient client progress-channel {:blob/keys [descriptor] :as blob-data}]
  (push-blob client progress-channel blob-data)
  (misc/log :info "%s: container configuration pushed" (.getDigest descriptor))
  descriptor)

(defn ^DescriptorDigest push-manifest
  "Pushes the image manifest for a specific tag into the target repository.

  Returns the digest of the pushed image."
  [^RegistryClient client progress-channel ^BuildableManifestTemplate manifest ^String tag]
  (let [^DescriptorDigest digest (.pushManifest client manifest tag)]
    (>!! progress-channel #:progress{:status :done})
    digest))

(defn- make-blob-data
  "Given a temporary directory and a file name contained therein,
  returns a map with the following namespaced keys:

  :blob/descriptor BlobDescriptor

  Object that contains properties describing a blob.

  :blob/read Function

  Function of no args that reads a blob from disk when invoked."
  [^File temp-dir ^String file-name]
  (let [make-input-stream               #(io/input-stream (io/file temp-dir file-name))
        ^BlobDescriptor blob-descriptor (Digests/computeDigest (make-input-stream))]
    #:blob{:descriptor blob-descriptor
           :reader     #(Blobs/from (make-input-stream))}))

(defn ^BuildableManifestTemplate make-buildable-manifest-template
  "Returns an instance of BuildableManifestTemplate containing the size
  and digest of the container configuration and the layers that
  compose the image to be pushed to the remote repository."
  [^BlobDescriptor container-config-descriptor layer-descriptors]
  (let [^BuildableManifestTemplate manifest (V22ManifestTemplate.)]
    (.setContainerConfiguration manifest (.getSize container-config-descriptor) (.getDigest container-config-descriptor))
    (run! (fn [^BlobDescriptor layer-descriptor]
            (.addLayer manifest (.getSize layer-descriptor) (.getDigest layer-descriptor)))
          layer-descriptors)
    manifest))

(defn- read-image-manifest
  "Reads the manifest.json extracted from the built tarball.

  This is the manifest.json generated by Jib. It has nothing to do
  with Vessel manifests."
  [^File source-dir]
  (first (misc/read-json (io/file source-dir "manifest.json"))))

(defn- throw-some
  "Throws the first Throwable found in coll as an ExceptionInfo or
  simply returns coll unchanged if no Throwable has been found."
  [coll]
  (some (fn [candidate]
          (when (instance? Throwable candidate)
            (throw (ex-info "One or more layers could not be pushed into remote registry"
                            #:vessel.error{:category  :vessel/push-error
                                           :throwable candidate}))))
        coll)
  coll)

(defn- push-layers
  "Pushes all layers into target registry in parallel.

  Returns a sequence of BlobDescriptor objects representing the pushed
  layers."
  [^RegistryClient client progress-channel ^File temp-dir layers]
  (let [input-channel  (async/to-chan layers)
        output-channel (async/chan)
        parallelism    (count layers)
        transducer     (comp  (map (partial make-blob-data temp-dir))
                              (map (partial push-layer client progress-channel)))]
    (async/pipeline-blocking parallelism
                             output-channel
                             transducer
                             input-channel)
    (->> output-channel
         (async/into [])
         <!!
         throw-some)))

(defn push
  "Pushes a tarball containing image layers and metadata into a remote registry.

  Options is a PersistentMap with the following meaningful keys:

  :tarball java.io.File

  The tar archive to be pushed.

  :temp-dir java.io.File

  Temporary directory where layers and metadata files will be
  extracted.

  :allow-insecure-registries Boolean

  When set to true, allow pushing images to insecure
  registries. Defaults to false.

  :anonymous Boolean

  When set to true, do not authenticate on the registry. Defaults to
  false."
  [{:keys [tarball temp-dir] :as options}]
  {:pre [tarball temp-dir]}
  (let [_                                 (extract-tarball tarball temp-dir)
        {:keys [config repoTags layers]}
        (read-image-manifest temp-dir)
        ^ImageReference image-reference   (ImageReference/parse (first repoTags))
        ^RegistryClient client            (make-registry-client image-reference options)
        progress-channel                  (progress-monitor temp-dir)
        layer-descriptors                 (push-layers client progress-channel temp-dir layers)
        ^BlobDescriptor config-descriptor (push-container-config client progress-channel (make-blob-data temp-dir config))
        ^DescriptorDigest digest          (push-manifest client progress-channel (make-buildable-manifest-template config-descriptor layer-descriptors) (.getTag image-reference))]
    (misc/log :info "%s: digest: %s" (.getTag image-reference) digest)))
