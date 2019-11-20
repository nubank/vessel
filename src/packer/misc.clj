(ns packer.misc
  (:require [clojure.java.io :as io]
            [clojure.string :as string])
  (:import clojure.lang.Sequential
           com.google.cloud.tools.jib.api.AbsoluteUnixPath
           java.io.File
           [java.nio.file Path Paths]
           java.util.ArrayList))

(defn sequential->java-list ^ArrayList
  [^Sequential seq]
  (java.util.ArrayList. seq))

(defn string->java-path ^Path
  [^String path]
  (Paths/get path (into-array String [])))

(defn string->absolute-unix-path ^AbsoluteUnixPath
  [^String path]
  (AbsoluteUnixPath/get path))

(defn log
  [level emitter message & args]
  (printf "%s [%s] %s%n"
          (string/upper-case (if (keyword? level)
                               (name level)
                               (str level)))
          emitter
          (apply format message args)))

(defn find-files-at
  [^File dir]
  (.listFiles dir))

(defmacro with-clean-dir
  "binding => [binding-symbol binding-value]
  binding-symbol => java.io.File

  Evaluates body in a context where the directory assigned to
  binding-symbol exists and is an empty directory."
  [binding & body]
  {:pre [(vector? binding) (= 2 (count binding))]}
  (let [[binding-symbol binding-value] binding]
    `(let [^File ~binding-symbol ~binding-value]
       (when (.exists ~binding-symbol)
         (run! #(io/delete-file %) (reverse (file-seq ~binding-symbol))))
       (.mkdir ~binding-symbol)
       ~@body)))
