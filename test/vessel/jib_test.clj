(ns vessel.jib-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer :all]
            [matcher-combinators.test :refer [match?]]
            [vessel.jib :as jib]
            [vessel.misc :as misc]
            [vessel.test-helpers :refer [ensure-clean-test-dir]])
  (:import org.apache.commons.vfs2.VFS))

(def tar-path "target/tests/jib-test/my-app.tar")

(defn read-from-tarball
  [^String file-name]
  (let [cwd (str (.getCanonicalFile (io/file ".")))
        tar-file (format "tar:%s/%s!/%s" cwd tar-path file-name)]
    (.. VFS getManager
        (resolveFile tar-file)
        getContent
        getInputStream)))

(use-fixtures :once (ensure-clean-test-dir))

(deftest ^:integration containerize-test
  (testing "calls Google Jib and containerize the files in question"
    (binding [misc/*verbose-logs* true]
        (jib/containerize #:image{:from
                                  #:image{:repository "openjdk" :tag "alpine"}
                                  :name
                                  #:image{:repository "my-app" :tag "v1"}
                                  :layers
                                  [#:image.layer{:name "resources"
                                                 :files [#:image.layer{:source "test/resources/greeting/greeting.txt"
                                                                       :target "/opt/app/WEB-INF/classes/greeting.txt"}]}]
                                  :tar-path tar-path}))

    (is (true? (misc/file-exists? (io/file tar-path)))))

  (is (match? [{:config "config.json"
                :repoTags ["my-app:v1"]
                :layers
                (comp not empty?)}]
              (misc/read-json (read-from-tarball "manifest.json")))))
