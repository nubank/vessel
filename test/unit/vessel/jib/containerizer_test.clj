(ns vessel.jib.containerizer-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer :all]
            [matcher-combinators.matchers :as m]
            [matcher-combinators.test :refer [match?]]
            [vessel.jib.containerizer :as jib.containerizer]
            [vessel.misc :as misc]
            [vessel.test-helpers :refer [ensure-clean-test-dir]])
  (:import org.apache.commons.vfs2.VFS))

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

(deftest containerize-test
  (testing "calls Google Jib and containerize the files in question"
    (let [greeting-file (io/file "test/resources/greeting/greeting.txt")]
      (binding [misc/*verbose-logs* true]
        (jib.containerizer/containerize #:image{:from
                                                #:image   {:repository "openjdk" :tag "sha256:1fd5a77d82536c88486e526da26ae79b6cd8a14006eb3da3a25eb8d2d682ccd6"}
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
                                                :tar-path tar-path})))

    (is (true? (misc/file-exists? (io/file tar-path)))))

  (is (match? [{:config   "config.json"
                :repoTags ["nubank/my-app:v1"]
                :layers
                (m/in-any-order ["8e3ba11ec2a2b39ab372c60c16b421536e50e5ce64a0bc81765c2e38381bcff6.tar.gz"
                                 "311ad0da45338842480bf25c6e6b7bb133b7b8cf709c3470db171ec370da5539.tar.gz"
                                 "df312c74ce16f20eeb87b5640db9b1579a53534bd3e9f3de1e916fc62744bcf4.tar.gz"
                                 "b57174a841843f24eb730afc0ea8829ccbc7845d8619065bf7565c0c68ea81fb.tar.gz"])}]
              (misc/read-json (read-from-tarball "manifest.json")))))
