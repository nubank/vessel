(ns vessel.jib.containerizer-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer :all]
            [matcher-combinators.test :refer [match?]]
            [vessel.jib.containerizer :as jib.containerizer]
            [vessel.misc :as misc]
            [vessel.test-helpers :refer [ensure-clean-test-dir]])
  (:import java.nio.file.Files
           java.time.Instant
           java.util.Arrays
           org.apache.commons.vfs2.VFS))

(def tar-path "target/tests/containerizer-test/my-app.tar")

(defn read-from-tarball
  [^String file-name]
  (let [cwd      (str (.getCanonicalFile (io/file ".")))
        tar-file (format "tar:%s/%s!/%s" cwd tar-path file-name)]
    (.. VFS getManager
        (resolveFile tar-file)
        getContent
        getInputStream)))

(use-fixtures :once (ensure-clean-test-dir))

(defn reproducible-image-spec
  [tar-path entries]
  #:image{:from          #:image{:tar-path "test/resources/valid-base-image.tar"}
          :user          "jetty"
          :name          #:image{:repository "nubank/my-app" :tag "v1"}
          :layers        [#:image.layer{:name "resources" :entries entries}]
          :creation-time Instant/EPOCH
          :tar-path      tar-path})

(deftest reproducible-containerize-test
  (testing "produces identical tarballs regardless of input entry order"
    (let [greeting-file (io/file "test/resources/greeting/greeting.txt")
          config-file   (io/file "test/resources/my-app/config/config.edn")
          entries       [#:layer.entry{:source            (.getPath greeting-file)
                                       :target            "/opt/app/greeting.txt"
                                       :modification-time Instant/EPOCH}
                         #:layer.entry{:source            (.getPath config-file)
                                       :target            "/opt/app/config.edn"
                                       :modification-time Instant/EPOCH}]
          first-tar     "target/tests/containerizer-test/reproducible-1.tar"
          second-tar    "target/tests/containerizer-test/reproducible-2.tar"]
      (jib.containerizer/containerize (reproducible-image-spec first-tar entries))
      (jib.containerizer/containerize (reproducible-image-spec second-tar (reverse entries)))
      (is (Arrays/equals (Files/readAllBytes (.toPath (io/file first-tar)))
                         (Files/readAllBytes (.toPath (io/file second-tar))))))))

(deftest containerize-test
  (testing "calls Google Jib and containerize the files in question"
    (let [greeting-file (io/file "test/resources/greeting/greeting.txt")
          layer-re      #"^[0-9a-f]{64}\.tar.gz$"]
      (binding [misc/*verbose-logs* true]
        (jib.containerizer/containerize #:image{:from
                                                #:image   {:repository "openjdk" :tag "sha256:1fd5a77d82536c88486e526da26ae79b6cd8a14006eb3da3a25eb8d2d682ccd6"}
                                                :user "jetty"
                                                :name
                                                #:image   {:repository "nubank/my-app" :tag "v1"}
                                                :layers
                                                [#:image.layer{:name    "resources"
                                                               :entries [#:layer.entry{:source            (.getPath greeting-file)
                                                                                       :target            "/opt/app/WEB-INF/classes/greeting.txt"
                                                                                       :file-permissions  #{"OTHERS_READ"
                                                                                                            "OWNER_WRITE"
                                                                                                            "OWNER_READ"
                                                                                                            "GROUP_READ"}
                                                                                       :modification-time (misc/last-modified-time greeting-file)}]}]
                                                :tar-path tar-path}))

      (is (true? (misc/file-exists? (io/file tar-path))))

      (is (match? [{:config   "config.json"
                    :repoTags ["nubank/my-app:v1"]
                    :layers
                    [layer-re
                     layer-re
                     layer-re
                     layer-re]}]
                  (misc/read-json (read-from-tarball "manifest.json")))))))
