(ns vessel.jib.helpers
  "Helper functions for dealing with orthogonal features of Google Jib."
  (:require [vessel.misc :as misc])
  (:import com.google.cloud.tools.jib.api.buildplan.AbsoluteUnixPath
           com.google.cloud.tools.jib.event.events.ProgressEvent
           com.google.cloud.tools.jib.tar.TarExtractor
           java.io.File))

(defn ^AbsoluteUnixPath string->absolute-unix-path
  [^String path]
  (AbsoluteUnixPath/get path))

(defn log-event-handler
  "Returns a consumer that handles log events triggered by Jib."
  [^String handler-name]
  (misc/java-consumer
   #(misc/log* (.getLevel %) handler-name (.getMessage %))))

(defn  progress-event-handler
  "Returns a consumer that handles progress events triggered by Jib."
  [^String handler-name]
  (misc/java-consumer (fn [^ProgressEvent progress-event]
                        (misc/log* :progress handler-name "%s (%.2f%%)"
                                   (.. progress-event getAllocation getDescription)
                                   (* (.. progress-event getAllocation getFractionOfRoot)
                                      (.getUnits progress-event)
                                      100)))))

(defn extract-tarball
  "Extracts the tarball into the specified destination."
  [^File tarball ^File destination]
  (TarExtractor/extract (misc/string->java-path (str tarball))
                        (misc/string->java-path (str destination))))
