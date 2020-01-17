(ns vessel.jib
  "Clojure wrapper for Google Jib."
  (:require [vessel.misc :as misc])
  (:import [com.google.cloud.tools.jib.api Containerizer ImageReference Jib JibContainerBuilder LayerConfiguration LayerConfiguration$Builder LogEvent RegistryImage TarImage]
           com.google.cloud.tools.jib.event.events.ProgressEvent
           com.google.cloud.tools.jib.frontend.CredentialRetrieverFactory))

(def ^:private log-event-handler
  (misc/java-consumer
   #(misc/log (.getLevel %) "jib" (.getMessage %))))

(def ^:private progress-event-handler
  (misc/java-consumer (fn [^ProgressEvent progress-event]
                        (misc/log :progress "jib" "%s (%.2f%%)"
                                  (.. progress-event getAllocation getDescription)
                                  (* (.. progress-event getAllocation getFractionOfRoot)
                                     (.getUnits progress-event)
                                     100)))))

(defn-   ^ImageReference make-image-reference
  "Returns a new image reference from the provided values."
  [{:image/keys [^String registry ^String repository ^String tag]}]
  (ImageReference/of registry repository tag))

(defn- ^Containerizer make-containerizer
  "Makes a new Jib containerizer object to containerize the application
  to a given tarball."
  [{:image/keys [name tar-path]}]
  {:pre [name tar-path]}
  (.. Containerizer
      (to (.. TarImage (at (misc/string->java-path tar-path))
              (named (make-image-reference name))))
      (setToolName "vessel")
      (addEventHandler LogEvent log-event-handler)
      (addEventHandler ProgressEvent progress-event-handler)))

(defn- containerize*
  [^JibContainerBuilder container-builder image-spec]
  (.containerize container-builder (make-containerizer image-spec)))

(defn- ^LayerConfiguration make-layer-configuration
  "Makes a LayerConfiguration object from the supplied data structure."
  [{:image.layer/keys [name files]}]
  (loop [^LayerConfiguration$Builder layer (.. LayerConfiguration builder (setName (clojure.core/name name)))
         files files]
    (if-not (seq files)
      (.build layer)
      (let [{:image.layer/keys [source target]} (first files)]
        (.addEntry layer (misc/string->java-path source) (misc/string->absolute-unix-path target))
        (recur layer (rest files))))))

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
  (let [retriever (.. CredentialRetrieverFactory (forImage image-reference log-event-handler)
                      dockerConfig)]
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
        (setCreationTime (misc/now)))))

(defn containerize
  "Given an image spec, containerize the application in question by
  producing a tarball with an OCI image."
  [{:image/keys [from layers] :as image-spec}]
  {:pre [from layers]}
  (-> (make-container-builder from)
      (add-layers layers)
      (containerize* image-spec)))
