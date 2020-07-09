(ns vessel.main
  (:gen-class)
  (:require [clojure.java.io :as io]
            [vessel.cli :as cli]
            [vessel.container-builder :as container-builder]
            [vessel.misc :as misc]
            [vessel.reader :as reader]))

(defn containerize
  "Containerization command."
  [{:keys [build-time-variables, manifest-path] :as options}]
  (-> (reader/read-manifest manifest-path build-time-variables)
      (container-builder/make-build-plan options)
      (container-builder/apply-build-plan options)))

(def vessel
  {:desc "A containerization tool for Clojure applications"
   :commands
   {"containerize"
    {:desc "Containerize a Clojure application"
     :fn containerize
     :opts [["-c" "--cache-dir directory"
             :id :cache-dir
             :desc "Directory to store cached layers. Defaults to ~/.vessel-cache"
             :parse-fn io/file
             :validate cli/file-or-dir-must-exist
             :default-fn (fn [_]
                           (misc/make-dir (misc/home-dir) ".vessel-cache"))]
            ["-d" "--digest file"
             :id :digest-path
             :desc "Path to write the digest of the built image."
             :parse-fn io/file]
            ["-i" "--image-reference file"
             :id :image-reference-path
             :desc "Path to write the qualified image reference of the built image: [registry]/<repository>@<digest>."
             :parse-fn io/file]
            ["-m" "--manifest file"
             :id :manifest-path
             :desc "manifest file describing the image to be built."
             :parse-fn io/file
             :validate cli/file-or-dir-must-exist]
            ["-p" "--project-dir directory"
             :id :project-dir
             :desc "root dir of the Clojure project to be built. Defaults to the same directory of the supplied manifest."
             :parse-fn io/file
             :validate cli/file-or-dir-must-exist
             :default-fn #(.getParent (:manifest-path %))]
            ["-t" "--tar-path file"
             :id :tar-path
             :desc "path to save the tarball containing the built image."
             :parse-fn io/file]]}

    "push"
    {:desc "Push a tarball to a registry"
     :fn nil
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
             :desc "Do not authenticate on the registry; push anonymously"]]}}})

(defn- exit
  "Terminates the JVM with the status code."
  [status]
  (System/exit status))

(defn -main
  [& args]
  (exit (cli/run vessel args)))

;; Scratch

(comment
  (let [target (misc/make-empty-dir "target/scratch") ]
    (containerize {:cache-dir (misc/make-dir (misc/home-dir) ".vessel-cache")
                   :digest-path (io/file target "digest.txt")
                   :image-reference-path (io/file target "image-reference.txt")
                   :manifest-path (io/file "vessel.edn")
                   :project-dir (io/file ".")
                   :tar-path (io/file target "vessel.tar")})))
