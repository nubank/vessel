(ns packer.jib
  "Clojure wrapper for Google Jib."
  (:require [packer.misc :as misc])
  (:import [com.google.cloud.tools.jib.api Containerizer ImageReference Jib JibContainerBuilder LogEvent RegistryImage TarImage]
           com.google.cloud.tools.jib.event.events.ProgressEvent
           com.google.cloud.tools.jib.frontend.CredentialRetrieverFactory
           java.time.Instant))

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

(defn- image-reference
  "Returns a new image reference from the provided values."
  ^ImageReference
  [{:image/keys [^String registry ^String repository ^String tag]}]
  (ImageReference/of registry repository tag))

(defn- containerizer
  [{:image/keys [name ^String tar-path]}]
  {:pre [name tar-path]}
  (.. Containerizer
      (to (.. TarImage (at (misc/string->java-path tar-path))
              (named (image-reference name))))
      (setToolName "packer")
      (addEventHandler LogEvent log-event-handler)
      (addEventHandler ProgressEvent progress-event-handler)))

(defn- containerize*
  [^JibContainerBuilder container-builder options]
  (.containerize container-builder (containerizer options)))

(defn- add-layers
  [^JibContainerBuilder container-builder layers]
  (let [strings->list-of-paths (comp misc/sequential->java-list (partial map misc/string->java-path))]
    (reduce (fn [builder {:image.layer/keys [source ^String target]}]
              (.addLayer builder (strings->list-of-paths source)
                         (misc/string->absolute-unix-path target)))
            container-builder layers)))

(defn- registry-image
  "Returns a new registry image."
  ^RegistryImage
  [from]
  (let [reference (image-reference from)
        retriever (.. CredentialRetrieverFactory (forImage reference log-event-handler)
                      dockerConfig)]
    (.. RegistryImage (named reference)
        (addCredentialRetriever retriever))))

(defn- container-builder
  "Returns a new container builder to start building the image.

  from represents the descriptor of the base image."
  ^JibContainerBuilder
  [from]
  (.. Jib (from (registry-image from))
      (setCreationTime (Instant/now))))

(defn containerize
  [{:image/keys [from layers] :as options}]
  {:pre [from layers]}
  (-> (container-builder from)
      (add-layers layers)
      (containerize* options)))
