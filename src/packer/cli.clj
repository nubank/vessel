(ns packer.cli
  (:gen-class)
  (:require [clojure.java.io :as io]
            [packer.api :as api]
            [clojure.tools.cli :as tools.cli]
            [packer.misc :as misc]
            [clojure.string :as string]))

(def ^:private cwd (io/file "."))

(def ^:private file-or-dir-must-exist
  [misc/file-exists? "no such file or directory"])

(def ^:private source-must-exist
  [#(misc/file-exists? (:source %)) "no such file or directory"])

(defn- accumulate
  [m k v]
  (update-in m [k] (fnil conj #{}) v))

(defn- parse-extra-file
  [value]
  (let [source+target (string/split value #"\s*:\s*")]
    (if (= 2 (count source+target))
      (zipmap [:source :target] (map io/file source+target))
      (throw (IllegalArgumentException.
              "Invalid extra-file format. Please, specify extra
    files in the form source:target")))))

(def help
  ["?" "--help"
   :id :help
   :desc "show this help and exit"])

(defn- parse-command
  [{:keys [fn options]} args]
  (tools.cli/parse-opts args
                        (cons help options)))

(def packer
  {"containerize"
   {:desc "Turns an input jar into a lightweight
  container according to provided options"
    :fn api/containerize
    :options [["-a" "--app-root PATH"
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
               :parse-fn parse-extra-file
               :validate source-must-exist
               :assoc-fn accumulate]
              ["-i" "--input JAR"
               :id :jar-file
               :desc "jar file to be containerized"
               :parse-fn io/file
               :validate file-or-dir-must-exist]
              ["-I" "--internal-deps REGEX"
               :id :internal-deps-re
               :desc "java regex to determine internal dependencies. Can be
            repeated many times for a logical or"
               :parse-fn re-pattern
               :assoc-fn accumulate]
              ["-m" "--manifest MANIFEST"
               :id :manifest
               :desc "manifest file describing the image to be built"
               :parse-fn io/file
               :validate file-or-dir-must-exist
               :assoc-fn #(assoc %1 %2 (misc/read-json %3))]
              ["-p" "--project-root PATH"
               :id :project-root
               :desc "root dir of the project containing the source
                      code. Packer inspects the project to obtain some useful
                      insights about how to organize layers."
               :default cwd
               :parse-fn io/file
               :validate file-or-dir-must-exist]
              ["-o" "--output PATH"
               :id :tarball
               :desc "path to save the tarball containing the built image"
               :default (io/file "image.tar")
               :parse-fn io/file]]}})
