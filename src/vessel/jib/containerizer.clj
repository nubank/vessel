(ns vessel.jib.containerizer
  "Containerization API built on top of Google Jib."
  (:require [vessel.jib.helpers :as jib.helpers]
            [vessel.misc :as misc])
  (:import [com.google.cloud.tools.jib.api Containerizer FilePermissions ImageFormat ImageReference Jib JibContainerBuilder LayerConfiguration LayerConfiguration$Builder LayerEntry LogEvent RegistryImage TarImage]
           com.google.cloud.tools.jib.event.events.ProgressEvent
           java.nio.file.attribute.PosixFilePermission))

(defn   ^ImageReference make-image-reference
  "Returns a new image reference from the provided values."
  [{:image/keys [^String registry ^String repository ^String tag]}]
  (ImageReference/of registry repository tag))

(defn- ^Containerizer make-containerizer
  "Makes a new Jib containerizer object to containerize the application
  to a given tarball."
  [{:image/keys [name tar-path]}]
  {:pre [name tar-path]}
  (let [cache-dir    (-> (misc/home-dir)
                         (misc/make-dir ".vessel-cache")
                         str
                         misc/string->java-path)
        handler-name "vessel.jib.containerizer"]
    (.. Containerizer
        (to (.. TarImage (at (misc/string->java-path tar-path))
                (named (make-image-reference name))))
        (setBaseImageLayersCache cache-dir)
        (setApplicationLayersCache cache-dir)
        (setToolName "vessel")
        (addEventHandler LogEvent (jib.helpers/log-event-handler handler-name))
        (addEventHandler ProgressEvent (jib.helpers/progress-event-handler handler-name)))))

(defn- containerize*
  [^JibContainerBuilder container-builder image-spec]
  (.containerize container-builder (make-containerizer image-spec)))

(defn- ^LayerEntry make-layer-entry
  "Creates a new LayerEntry object from the supplied values."
  [{:layer.entry/keys [source target file-permissions modification-time]}]
  (let [permissions (some->> file-permissions
                             (map #(PosixFilePermission/valueOf %))
                             set
                             (FilePermissions/fromPosixFilePermissions))]
    (LayerEntry. (misc/string->java-path source)
                 (jib.helpers/string->absolute-unix-path target)
                 (or permissions FilePermissions/DEFAULT_FILE_PERMISSIONS)
                 modification-time)))

(defn- ^LayerConfiguration make-layer-configuration
  "Makes a LayerConfiguration object from the supplied data structure."
  [{:image.layer/keys [name entries]}]
  (loop [^LayerConfiguration$Builder layer (.. LayerConfiguration builder (setName name))
         entries                           entries]
    (if-not (seq entries)
      (.build layer)
      (let [layer-entry (first entries)]
        (.addEntry layer (make-layer-entry layer-entry))
        (recur layer (rest entries))))))

(defn- ^JibContainerBuilder add-layers
  "Adds the supplied layers to the JibContainerBuilder object as a set
  of LayerConfiguration instances."
  [^JibContainerBuilder container-builder layers]
  (reduce (fn [builder layer]
            (.addLayer builder (make-layer-configuration layer)))
          container-builder layers))

(defn-   ^RegistryImage make-registry-image
  "Given an ImageReference instance, returns a new registry image
  object."
  [^ImageReference image-reference]
  (let [^CredentialRetriever retriever (jib.helpers/make-docker-config-retriever image-reference)]
    (.. RegistryImage (named image-reference)
        (addCredentialRetriever retriever))))

(defn-   ^Boolean is-in-docker-hub?
  "Is the image in question stored in the official Docker hub?"
  [^ImageReference image-reference]
  (= "registry-1.docker.io"
     (.getRegistry image-reference)))

(defn-   ^JibContainerBuilder make-container-builder
  "Returns a new container builder to start building the image.

  from is a map representing the base image descriptor. The following
  keys are meaningful: :image/registry, :image/repository
  and :image/tag. The :image/registry and :image/tag are optional."
  [from]
  (let [^ImageReference reference (make-image-reference from)]
    (.. Jib (from
             (if (is-in-docker-hub? reference)
               (str reference)
               (make-registry-image reference)))
        (setCreationTime (misc/now))
        (setFormat ImageFormat/Docker))))

(defn containerize
  "Given an image spec, containerize the application in question by
  producing a tarball containing image layers and metadata files."
  [{:image/keys [from layers] :as image-spec}]
  {:pre [from layers]}
  (-> (make-container-builder from)
      (add-layers layers)
      (containerize* image-spec)))
