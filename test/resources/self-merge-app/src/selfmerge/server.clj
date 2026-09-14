(ns selfmerge.server
  (:gen-class)
  (:require [ownership]
            [clojure.edn :as edn]
            [clojure.java.io :as io]))

(defn -main [& args]
  (-> (io/resource "config.edn")
      slurp
      edn/read-string
      prn))
