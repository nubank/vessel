(ns vessel.jib.containerizer-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer :all]
            [matcher-combinators.matchers :as m]
            [matcher-combinators.test :refer [match?]]
            [vessel.jib.containerizer :as jib.containerizer]
            [vessel.misc :as misc]
            [vessel.test-helpers :refer [ensure-clean-test-dir]])
  (:import java.io.File
           org.apache.commons.vfs2.VFS))

(def tar-path-1 "target/tests/containerizer-test/my-app-1.tar")

(def tar-path-2 "target/tests/containerizer-test/my-app-2.tar")

(defn read-from-tarball
  [^File tarball ^String file-name]
  (let [cwd      (str (.getCanonicalFile (io/file ".")))
        tar-file (format "tar:%s/%s!/%s" cwd tarball file-name)]
    (.. VFS getManager
        (resolveFile tar-file)
        getContent
        getInputStream)))

(use-fixtures :once (ensure-clean-test-dir))

(deftest containerize-test
  (testing "calls Google Jib and containerize the files in question"
    (let [class-file (io/file "test/resources/containerization/fake_class.class")
          resource   (io/file "test/resources/containerization/resource.edn")
          build-result
          (binding [misc/*verbose-logs* true]
            (jib.containerizer/containerize #:image{:from
                                                    #:image   {:repository "openjdk" :tag "sha256:1fd5a77d82536c88486e526da26ae79b6cd8a14006eb3da3a25eb8d2d682ccd6"}
                                                    :name
                                                    #:image   {:repository "nubank/my-app" :tag "v1"}
                                                    :layers
                                                    [#:image.layer{:name    "sources"
                                                                   :kind    :file-entry
                                                                   :entries [#:layer.entry{:source            (.getPath class-file)
                                                                                           :target            "/opt/app/WEB-INF/classes/fake_class.class"
                                                                                           :file-permissions  #{"OTHERS_READ"
                                                                                                                "OWNER_WRITE"
                                                                                                                "OWNER_READ"
                                                                                                                "GROUP_READ"}
                                                                                           :modification-time (misc/last-modified-time class-file)}]}
                                                     #:image.layer{:name    "resources"
                                                                   :kind    :file-entry
                                                                   :entries [#:layer.entry{:source            (.getPath resource)
                                                                                           :target            "/opt/app/WEB-INF/classes/resource.edn"
                                                                                           :file-permissions  #{"OTHERS_READ"
                                                                                                                "OWNER_WRITE"
                                                                                                                "OWNER_READ"
                                                                                                                "GROUP_READ"}
                                                                                           :modification-time (misc/last-modified-time resource)}]}]
                                                    :tar-path tar-path-1}))]

      (is (true? (misc/file-exists? (io/file tar-path-1)))
          "the tarball has been created")

      (is (match? [{:Config   "config.json"
                    :RepoTags ["nubank/my-app:v1"]
                    :Layers
                    (m/in-any-order ["8e3ba11ec2a2b39ab372c60c16b421536e50e5ce64a0bc81765c2e38381bcff6.tar.gz",
                                     "311ad0da45338842480bf25c6e6b7bb133b7b8cf709c3470db171ec370da5539.tar.gz",
                                     "df312c74ce16f20eeb87b5640db9b1579a53534bd3e9f3de1e916fc62744bcf4.tar.gz",
                                     "50498562631978650bb9ed9624c9f0089f6a97e532bc03db62582c9cf83d9d54.tar.gz",
                                     "79025e0820afb1c1d8e307709df12e785df88d583a928c626e1f5ef8999daa64.tar.gz"])}]
                  (misc/read-json (read-from-tarball tar-path-1 "manifest.json")))
          "the image manifest has the expected layers")

      (is (= "nubank/my-app:v1"
             (:image/reference build-result))
          "the containerizer returned the correct image reference")

      (is (re-find #"^sha256:[a-f0-9]{64}$"
                   (:image/digest build-result))
          "the containerizer returned a valid image digest")))

  (testing "builds a new image now retrieving the layer from the cache as per
  specified in the build plan"
    (let [resource (io/file "test/resources/containerization/resource.edn")]
      (binding [misc/*verbose-logs* true]
        (jib.containerizer/containerize #:image{:from
                                                #:image   {:repository "openjdk" :tag "sha256:1fd5a77d82536c88486e526da26ae79b6cd8a14006eb3da3a25eb8d2d682ccd6"}
                                                :name
                                                #:image   {:repository "nubank/my-app" :tag "v1"}
                                                :layers
                                                [#:image.layer{:name   "sources"
                                                               :kind   :cached-layer
                                                               :digest "sha256:50498562631978650bb9ed9624c9f0089f6a97e532bc03db62582c9cf83d9d54"}
                                                 #:image.layer{:name    "resources"
                                                               :kind    :file-entry
                                                               :entries [#:layer.entry{:source            (.getPath resource)
                                                                                       :target            "/opt/app/WEB-INF/classes/resource.edn"
                                                                                       :modification-time (misc/last-modified-time resource)}]}]
                                                :tar-path tar-path-2}))

      (is (true? (misc/file-exists? (io/file tar-path-2)))
          "the tarball has been created")

      (is (match? [{:Config   "config.json"
                    :RepoTags ["nubank/my-app:v1"]
                    :Layers
                    (m/in-any-order ["8e3ba11ec2a2b39ab372c60c16b421536e50e5ce64a0bc81765c2e38381bcff6.tar.gz",
                                     "311ad0da45338842480bf25c6e6b7bb133b7b8cf709c3470db171ec370da5539.tar.gz",
                                     "df312c74ce16f20eeb87b5640db9b1579a53534bd3e9f3de1e916fc62744bcf4.tar.gz",
                                     "50498562631978650bb9ed9624c9f0089f6a97e532bc03db62582c9cf83d9d54.tar.gz",
                                     "79025e0820afb1c1d8e307709df12e785df88d583a928c626e1f5ef8999daa64.tar.gz"])}]
                  (misc/read-json (read-from-tarball tar-path-2 "manifest.json")))
          "the image manifest has the same layers as the first one"))))
