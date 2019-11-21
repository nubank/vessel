(ns packer.jib
  "Data driven wrapper for Google Jib."
  (:require [packer.misc :as misc])
  (:import [com.google.cloud.tools.jib.api Containerizer Jib JibContainerBuilder LogEvent TarImage]
           com.google.cloud.tools.jib.event.events.ProgressEvent))

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

(defn- containerizer
  [{:image/keys [^String name ^String tar-archive]}]
  {:pre [name tar-archive]}
  (.. Containerizer
      (to (.. TarImage (at (misc/string->java-path tar-archive))
              (named name)))
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

(defn containerize
  [{:image/keys [^String from layers] :as options}]
  {:pre [from layers]}
  (-> (Jib/from from)
      (add-layers layers)
      (containerize* options)))
