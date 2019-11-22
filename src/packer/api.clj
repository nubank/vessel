(ns packer.api
  (:require [clojure.java.io :as io]
            [packer.image :as image]
            [packer.jib :as jib]
            [packer.misc :as misc :refer [with-clean-dir]])
  (:import java.io.File
           [java.util.jar JarEntry JarFile]))

(defn- unpack-jar
  "Extracts all files from the jar in question into the destination
  directory.

  Returns a shallow list of the extracted files."
  [^File jar ^File dest-dir]
  (with-open [jar-file (JarFile. jar)]
    (misc/log :info "packer" "extracting %s into %s directory..." (.getName jar) (.getPath dest-dir))
    (->> jar-file
         .entries
         enumeration-seq
         (run! (fn [^JarEntry jar-entry]
                 (let [^File file (io/file dest-dir (.getName jar-entry))]
                   (if (.isDirectory jar-entry)
                     (.mkdir file)
                     (do (io/make-parents file)
                         (io/copy (.getInputStream jar-file jar-entry) file)))))))
    (misc/find-files-at dest-dir)))

(defn containerize
  "Turns an input jar into a lightweight container according to the
  provided options."
  [{:keys [app-root input manifest output]}]
  {:pre [input manifest output]}
  (with-clean-dir [dest-dir (io/file ".packer")]
    (let [all-files (unpack-jar input dest-dir)
          image-descriptor (image/describe-image manifest {:files all-files
                                                           :known-source-files ["heimdall"
                                                                                :app-root app-root
                                                                                :output output]})]
      (jib/containerize image-descriptor))))

(comment (containerize {:image-name "heimdall"
                        :input (io/file "/home/alangh/dev/nu/heimdall/target/heimdall-0.1.0-SNAPSHOT-standalone.jar")
                        :output "heimdall.tar"}))
