(ns packer.cli
  (:gen-class)
  (:require [clojure.java.io :as io]
            [packer.misc :as misc]))

(def ^:private cwd (io/file "."))

(defn- file-or-dir-must-exist
  [option-name]
  [misc/file-exists? (format "%s: no such file or directory" option-name)])

(defn- accumulate
  [m k v]
  (update-in m [k] (fnil conj #{}) v))

(def help
  ["?" "--help"
   :id :help
   :desc "show this help and exit"])

(defn- parse-command
  [program [command & args]]
  (tools.cli/parse-opts args
                        (cons help (get program command))))

(def packer
  {"containerize"
   {:desc "Turns an input jar into a lightweight
  container according to provided options"
    :opts [["-a" "--app-root PATH"
            :id :app-root
            :desc "app root of the container image. Classes and
                      resource files will be copied to relative paths to the app
                      root."
            :default (io/file "/app")
            :parse-fn io/file]
           ["-e" "--extra-file PATH"
            :id :extra-files
            :desc "extra files to be copied to the container
                      image. The value must be passed in the form source:target
                      and this option can be repeated many times"
            :assoc-fn accumulate]
           ["-i" "--input JAR"
            :id :jar-file
            :desc "jar file to be containerized"
            :parse-fn io/file
            :validate (file-or-dir-must-exist "input")]
           ["-m" "--manifest MANIFEST"
            :id :manifest
            :desc "manifest file describing the image to be built"
            :parse-fn io/file
            :validate (file-or-dir-must-exist "manifest")]
           ["-p" "--project-root PATH"
            :id :project-root
            :desc "root dir of the project containing the source
                      code. Packer inspects the project to obtain some useful
                      insights about how to organize layers."
            :default cwd
            :parse-fn io/file
            :validate (file-or-dir-must-exist "project-root")]
           ["-o" "--output PATH"
            :id :tarball
            :desc "path to save the tarball containing the built image"
            :default (io/file "image.tar")
            :parse-fn io/file]]}})

(parse-command packer ["containerize"])
