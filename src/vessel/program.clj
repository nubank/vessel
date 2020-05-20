(ns vessel.program
  (:gen-class)
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [vessel.api :as api]
            [vessel.cli :as cli]
            [vessel.misc :as misc]))

(def verbose ["-v" "--verbose"
              :id :verbose?
              :desc "Show verbose messages"])

(defn- exit
  "Terminates the JVM with the status code."
  [status]
  (System/exit status))

(def vessel
  {:desc "A containerization tool for Clojure applications"
   :commands
   {"containerize"
    {:desc "Containerize a Clojure application"
     :fn   api/containerize
     :opts [["-a" "--app-root PATH"
             :id :app-root
             :desc "app root of the container image. Classes and resource files will be copied to relative paths to the app root"
             :default (io/file "/app")
             :parse-fn io/file]
            ["-c" "--classpath PATHS"
             :id :classpath-files
             :desc "Directories and zip/jar files on the classpath in the same format expected by the java command"
             :required? true
             :parse-fn (comp set (partial map io/file) #(string/split % #":"))]
            ["-e" "--extra-path PATH"
             :id :extra-paths
             :desc "extra files to be copied to the container image. The value must be passed in the form source:target or source:target@churn and this option can be repeated many times"
             :parse-fn cli/parse-extra-path
             :validate cli/source-must-exist
             :assoc-fn cli/repeat-option]
            ["-i" "--internal-deps REGEX"
             :id :internal-deps-re
             :desc "java regex to determine internal dependencies. Can be repeated many times for a logical or effect"
             :parse-fn re-pattern
             :assoc-fn cli/repeat-option]
            ["-m" "--main-class NAMESPACE"
             :id :main-class
             :desc "Namespace that contains the application's entrypoint, with a :gen-class directive and a -main function"
             :required? true
             :parse-fn symbol]
            ["-M" "--manifest PATH"
             :id :manifest
             :desc "manifest file describing the image to be built"
             :required? true
             :parse-fn io/file
             :validate cli/file-or-dir-must-exist
             :assoc-fn #(assoc %1 %2 (misc/read-json %3))]
            ["-o" "--output PATH"
             :id :tarball
             :desc "path to save the tarball containing the built image"
             :default (io/file "image.tar")
             :parse-fn io/file]
            ["-p" "--preserve-file-permissions"
             :id :preserve-file-permissions?
             :desc "Preserve original file permissions when copying files to the container. If not enabled, the default permissions for files are 644"]
            ["-s" "--source-path PATH"
             :id :source-paths
             :desc "Directories containing source files. This option can be repeated many times"
             :default-fn (constantly #{(io/file "src")})
             :default-desc "src"
             :parse-fn io/file
             :validate cli/file-or-dir-must-exist
             :assoc-fn cli/repeat-option]
            ["-r" "--resource-path PATH"
             :id :resource-paths
             :desc "Directories containing resource files. This option can be repeated many times"
             :default-fn (constantly #{(io/file "resources")})
             :default-desc "resources"
             :parse-fn io/file
             :validate cli/file-or-dir-must-exist
             :assoc-fn cli/repeat-option]
            verbose]}

    "image"
    {:desc "Generate an image manifest, optionally by extending a base image and/or merging other manifests"
     :fn   api/image
     :opts
     [["-a" "--attribute KEY-VALUE"
       :id :attributes
       :desc "Add the attribute in the form key:value to the manifest. This option can be repeated multiple times"
       :parse-fn cli/parse-attribute
       :assoc-fn cli/repeat-option]
      ["-b" "--base-image PATH"
       :id :base-image
       :desc "Manifest file describing the base image"
       :parse-fn io/file
       :validate cli/file-or-dir-must-exist
       :assoc-fn #(assoc %1 %2 (misc/read-json %3))]
      ["-m" "--merge-into PATH"
       :id :manifests
       :desc "Manifest file to be merged into the manifest being created. This option can be repeated multiple times"
       :parse-fn io/file
       :validate cli/file-or-dir-must-exist
       :assoc-fn #(cli/repeat-option %1 %2 (misc/read-json %3))]
      ["-o" "--output PATH"
       :id :output
       :desc "Write the manifest to path instead of stdout"
       :default *out*
       :default-desc "stdout"
       :parse-fn (comp io/writer io/file)]
      ["-r" "--registry REGISTRY"
       :id :registry
       :desc "Image registry"
       :default "docker.io"]
      ["-R" "--repository REPOSITORY"
       :id :repository
       :desc "Image repository"]
      ["-t" "--tag TAG"
       :id :tag
       :desc "Image tag. When omitted uses a SHA-256 digest of the resulting manifest"]]}

    "manifest"
    {:desc "Generate arbitrary manifests"
     :fn   api/manifest
     :opts
     [["-a" "--attribute KEY-VALUE"
       :id :attributes
       :desc "Add the attribute in the form key:value to the manifest. This option can be repeated multiple times"
       :default []
       :parse-fn cli/parse-attribute
       :assoc-fn cli/repeat-option]
      ["-o" "--output PATH"
       :id :output
       :desc "Write the manifest to path instead of stdout"
       :default *out*
       :default-desc "stdout"
       :parse-fn (comp io/writer io/file)]
      ["-O" "--object OBJECT"
       :id :object
       :desc "Object under which attributes will be added"
       :parse-fn keyword]]}

    "push"
    {:desc "Push a tarball to a registry"
     :fn   api/push
     :opts [["-t" "--tarball PATH"
             :id :tarball
             :desc "Tar archive containing image layers and metadata files"
             :parse-fn io/file
             :validate cli/file-or-dir-must-exist]
            ["-a" "--allow-insecure-registries"
             :id :allow-insecure-registries?
             :desc "Allow pushing images to insecure registries"]
            ["-A" "--anonymous"
             :id :anonymous?
             :desc "Do not authenticate on the registry; push anonymously"]
            verbose]}}})

(defn -main
  [& args]
  (exit (cli/run vessel args)))
