(ns packer.manifests
  (:require [packer.git :as git]
            [clojure.data.json :as json]
            [clojure.java.io :as io])
  (:import java.io.File))

(defn- default-attrs
  [^File working-dir]
  {:branch (git/current-branch :working-dir working-dir)
   :version (git/rev-parse-head :working-dir working-dir)})

(defn write-manifest
  [{:keys [name object output type  working-dir]}]
  (->> {object
        (merge {:name name
                :type type}
               (default-attrs working-dir))}
       (json/write-str)
       (spit output)))
