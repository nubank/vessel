(ns vessel.api
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [vessel.builder :as builder]
            [vessel.image :as image]
            [vessel.jib.containerizer :as jib.containerizer]
            [vessel.jib.pusher :as jib.pusher]
            [vessel.misc :as misc])
  (:import java.io.Writer))

(def vessel-dir (io/file ".vessel"))

(defmacro ^:private with-elapsed-time
  "Evaluates body and shows a log message by displaying the elapsed time in the process.

  Returns whatever body yelds."
  [^String message & body]
  `(let [start#  (misc/now)
         result# (do ~@body)]
     (misc/log :info "%s %s"
               ~message
               (misc/duration->string (misc/duration-between start# (misc/now))))
     result#))

(defn containerize
  "Containerizes a Clojure application."
  [{:keys [verbose?] :as options}]
  (binding [misc/*verbose-logs* verbose?]
    (with-elapsed-time "Successfully containerized in"
      (let [opts (assoc options :target-dir (misc/make-empty-dir vessel-dir))]
        (-> (builder/build-app opts)
            (image/render-image-spec opts)
            jib.containerizer/containerize)))))

(defn- write-manifest
  "Writes the manifest to the output as a JSON object."
  [^Writer output manifest]
  (binding [*out* output]
    (println (json/write-str manifest))))

(defn manifest
  [{:keys [attributes  object output]}]
  {:pre [attributes  object output]}
  (->> (into {}  attributes)
       (assoc {} object)
       (write-manifest output)))

(defn image
  [{:keys [attributes base-image manifests output registry repository tag]}]
  {:pre [output registry repository]}
  (let [merge-all      (partial apply merge)
        image-manifest (-> {:image (into {:repository repository :registry registry} attributes)}
                           (misc/assoc-some :base-image base-image)
                           (merge-all manifests))
        image-tag      (or tag (misc/sha-256 image-manifest))]
    (write-manifest output (assoc-in image-manifest [:image :tag] image-tag))))

(defn push
  "Pushes a tarball to a registry."
  [{:keys [verbose-logs?] :as options}]
  (binding [misc/*verbose-logs* verbose-logs?]
    (with-elapsed-time "Successfully pushed in"
      (jib.pusher/push
       (assoc options :temp-dir (misc/make-empty-dir vessel-dir))))))
