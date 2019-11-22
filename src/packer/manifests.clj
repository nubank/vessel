(ns packer.manifests
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [packer.git :as git])
  (:import java.io.File))

(defn- default-attrs
  [^File working-dir]
  {:branch (git/current-branch :working-dir working-dir)
   :version (git/rev-parse-head :working-dir working-dir)})

(defn gen-manifest
  [{:keys [attributes object output working-dir]
    :or {working-dir (io/file ".")}}]
  {:pre [attributes object output]}
  (->> (into (default-attrs working-dir) attributes)
       (assoc {} object)
       json/write-str
       (spit output)))
