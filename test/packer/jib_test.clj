(ns packer.jib-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer :all]
            [matcher-combinators.test :refer [match?]]
            [packer.jib :as jib]
            [packer.misc :as misc])
  (:import org.apache.commons.vfs2.VFS))

(defn read-from-tarball
  [^String file-name]
  (let [cwd (str (.getCanonicalFile (io/file ".")))
        tar-file (format "tar:%s/target/my-app.tar!/%s" cwd file-name)]
    (.. VFS getManager
        (resolveFile tar-file)
        getContent
        getInputStream)))

(deftest ^:integration containerize-test
  (testing "calls Google Jib and containerize the files in question"
    (jib/containerize #:image{:from
                              #:image{:repository "openjdk" :tag "alpine"}
                              :name
                              #:image{:repository "my-app" :tag "v1"}
                              :layers
                              [#:image.layer{:name :resources
                                             :source ["test/resources/fixtures/greeting.txt"]
                                             :target "/opt/app/WEB-INF/classes"}]
                              :tar-path "target/my-app.tar"})

    (is (true? (misc/file-exists? (io/file "target/my-app.tar")))))

  (is (match? [{:config "config.json"
                :repoTags ["my-app:v1"]
                :layers
                (comp not empty?)}]
              (misc/read-json (read-from-tarball "manifest.json")))))
