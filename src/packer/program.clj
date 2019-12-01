(ns packer.program
  (:gen-class)
  (:require [clojure.java.io :as io]
            [packer.api :as api]
            [packer.cli :as cli]
            [packer.misc :as misc]))

(def ^:private cwd
  "Current working directory."
  (io/file "."))

(def packer
  {:desc "Containerization tool for Clojure applications"
   :commands
   {"containerize"
    {:desc "Turns an input jar into a lightweight
  container according to provided options"
     :fn api/containerize
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
             :parse-fn cli/parse-extra-file
             :validate cli/source-must-exist
             :assoc-fn cli/repeat-option]
            ["-i" "--input JAR"
             :id :jar-file
             :desc "jar file to be containerized"
             :parse-fn io/file
             :validate cli/file-or-dir-must-exist]
            ["-I" "--internal-deps REGEX"
             :id :internal-deps-re
             :desc "java regex to determine internal dependencies. Can be
            repeated many times for a logical or"
             :parse-fn re-pattern
             :assoc-fn cli/repeat-option]
            ["-m" "--manifest PATH"
             :id :manifest
             :desc "manifest file describing the image to be built"
             :parse-fn io/file
             :validate cli/file-or-dir-must-exist
             :assoc-fn #(assoc %1 %2 (misc/read-json %3))]
            ["-p" "--project-root PATH"
             :id :project-root
             :desc "root dir of the project containing the source
                      code. Packer inspects the project to obtain some useful
                      insights about how to organize layers."
             :default cwd
             :parse-fn io/file
             :validate cli/file-or-dir-must-exist]
            ["-o" "--output PATH"
             :id :tarball
             :desc "path to save the tarball containing the built image"
             :default (io/file "image.tar")
             :parse-fn io/file]]}}})

(defn -main
  [& args]
  (try
    (cli/run-packer packer args)
    (catch Exception e
      (cli/show-errors {:errors [(.getMessage e)]})
      1)))
