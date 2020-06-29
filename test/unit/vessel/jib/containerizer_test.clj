(ns vessel.jib.containerizer-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer :all]
            [matcher-combinators.matchers :as m]
            [matcher-combinators.test :refer [match?]]
            [vessel.jib.containerizer :as jib.containerizer]
            [vessel.misc :as misc]
            [vessel.test-helpers :refer [ensure-clean-test-dir read-from-tarball]])
  (:import java.time.Instant))

(use-fixtures :once (ensure-clean-test-dir))

(def tar-path-1 (io/file "target/tests/containerizer-test/my-app-1.tar"))

(def tar-path-2 (io/file "target/tests/containerizer-test/my-app-2.tar"))

(def cache-dir (misc/make-dir (misc/home-dir) ".vessel-cache"))

;; Make layers deterministic.
(def fixed-modification-time
  (Instant/ofEpochMilli 0))

(deftest containerize-test
  (testing "calls Google Jib and containerize the files in question"
    (let [class-file (io/file "test/resources/containerization/fake_class.class")
          resource   (io/file "test/resources/containerization/resource.edn")
          build-result
          (binding [misc/*verbose-logs* true]
            (jib.containerizer/containerize #:image{:from   "openjdk@sha256:1fd5a77d82536c88486e526da26ae79b6cd8a14006eb3da3a25eb8d2d682ccd6"
                                                    :target "nubank/my-app:v1"
                                                    :layers
                                                    [#:layer{:id      :source-paths
                                                             :origin  :file-entries
                                                             :entries [#:layer.entry{:source            (.getPath class-file)
                                                                                     :target            "/opt/app/WEB-INF/classes/fake_class.class"
                                                                                     :file-permissions  #{"OTHERS_READ"
                                                                                                          "OWNER_WRITE"
                                                                                                          "OWNER_READ"
                                                                                                          "GROUP_READ"}
                                                                                     :modification-time fixed-modification-time}]}
                                                     #:layer{:id      :resource-paths
                                                             :origin  :file-entries
                                                             :entries [#:layer.entry{:source            (.getPath resource)
                                                                                     :target            "/opt/app/WEB-INF/classes/resource.edn"
                                                                                     :file-permissions  #{"OTHERS_READ"
                                                                                                          "OWNER_WRITE"
                                                                                                          "OWNER_READ"
                                                                                                          "GROUP_READ"}
                                                                                     :modification-time fixed-modification-time}]}]}
                                            {:cache-dir cache-dir :tar-path tar-path-1}))]

      (is (true? (misc/file-exists? (io/file tar-path-1)))
          "the tarball has been created")

      (is (match? [{:Config   "config.json"
                    :RepoTags ["nubank/my-app:v1"]
                    :Layers
                    (m/in-any-order ["8e3ba11ec2a2b39ab372c60c16b421536e50e5ce64a0bc81765c2e38381bcff6.tar.gz",
                                     "311ad0da45338842480bf25c6e6b7bb133b7b8cf709c3470db171ec370da5539.tar.gz",
                                     "df312c74ce16f20eeb87b5640db9b1579a53534bd3e9f3de1e916fc62744bcf4.tar.gz",
                                     "05edcc9e7f16871319590dccb2f9045f168a2cbdfab51b35b98693e57d42f7f7.tar.gz",
                                     "86695d056ba72013e01b4c1363b63f63fdb90664adbbe043df1354c98c920906.tar.gz"])}]
                  (misc/read-json (read-from-tarball tar-path-1 "manifest.json")))
          "the image manifest has the expected layers")

      (is (re-find #"^nubank/my-app@sha256:[a-f0-9]{64}$"
                   (:image/reference build-result))
          "the containerizer returned the correct image reference")

      (is (re-find #"^sha256:[a-f0-9]{64}$"
                   (:image/digest build-result))
          "the containerizer returned a valid image digest")

      (is (= {:source-paths
              "sha256:05edcc9e7f16871319590dccb2f9045f168a2cbdfab51b35b98693e57d42f7f7"
              :resource-paths
              "sha256:86695d056ba72013e01b4c1363b63f63fdb90664adbbe043df1354c98c920906"}
             (:application.layers/digests build-result))
          "the build result contains a map of layer ids to their corresponding digests")))

  (testing "builds a new image now retrieving the layer from the cache as per
  specified in the build plan"
    (let [resource (io/file "test/resources/containerization/resource.edn")]
      (binding [misc/*verbose-logs* true]
        (jib.containerizer/containerize #:image{:from   "openjdk@sha256:1fd5a77d82536c88486e526da26ae79b6cd8a14006eb3da3a25eb8d2d682ccd6"
                                                :target "nubank/my-app:v1"
                                                :layers
                                                [#:layer{:id     :source-paths
                                                         :origin :extended-cache
                                                         :digest "sha256:05edcc9e7f16871319590dccb2f9045f168a2cbdfab51b35b98693e57d42f7f7"}
                                                 #:layer{:id      :resource-paths
                                                         :origin  :file-entries
                                                         :entries [#:layer.entry{:source            (.getPath resource)
                                                                                 :target            "/opt/app/WEB-INF/classes/resource.edn"
                                                                                 :modification-time fixed-modification-time}]}]}
                                        {:cache-dir cache-dir :tar-path tar-path-2}))

      (is (true? (misc/file-exists? (io/file tar-path-2)))
          "the tarball has been created")

      (is (match? [{:Config   "config.json"
                    :RepoTags ["nubank/my-app:v1"]
                    :Layers
                    (m/in-any-order ["8e3ba11ec2a2b39ab372c60c16b421536e50e5ce64a0bc81765c2e38381bcff6.tar.gz",
                                     "311ad0da45338842480bf25c6e6b7bb133b7b8cf709c3470db171ec370da5539.tar.gz",
                                     "df312c74ce16f20eeb87b5640db9b1579a53534bd3e9f3de1e916fc62744bcf4.tar.gz",
                                     "05edcc9e7f16871319590dccb2f9045f168a2cbdfab51b35b98693e57d42f7f7.tar.gz",
                                     "86695d056ba72013e01b4c1363b63f63fdb90664adbbe043df1354c98c920906.tar.gz"])}]
                  (misc/read-json (read-from-tarball tar-path-2 "manifest.json")))
          "the image manifest has the same layers as the first one"))))
