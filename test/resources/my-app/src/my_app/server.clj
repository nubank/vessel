(ns my-app.server
  (:gen-class)
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]))

(defn -main [& args]
  (-> (io/resource "resource1.edn")
      slurp
      edn/read-string
      json/write-str
      println))
