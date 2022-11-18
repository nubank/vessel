(ns vessel.program-integration-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer :all]
            [matcher-combinators.test :refer [match?]]
            [mockfn.macros :refer [calling providing]]
            [vessel.misc :as misc]
            [vessel.program :as vessel]
            [vessel.test-helpers :refer [classpath ensure-clean-test-dir]]))

(def project-dir (io/file "test/resources/my-app"))
(def target-dir (io/file "target/tests/program-integration-test"))

(def registry
  "Name of the Docker registry to be used in the test suite.

  Defaults to localhost, but it may be overwritten through the
  environment variable VESSEL_TEST_REGISTRY."
  (or (System/getenv "VESSEL_TEST_REGISTRY")
      "localhost"))

(use-fixtures :once (ensure-clean-test-dir))

(deftest vessel-test
  (providing [(#'vessel/exit int?) (calling identity)]

             (testing "generates a manifest file containing metadata about the
               application in question"
               (is (zero? (vessel/-main "manifest"
                                        "--attribute" "name:my-app"
                                        "--attribute" "git-commit:07dccc801700cbe28e4a428e455b4627e0ab4ba9"
                                        "--object" "service"
                                        "--output" (str (io/file target-dir "my-app.json")))))

               (is (= {:service {:name       "my-app"
                                 :git-commit "07dccc801700cbe28e4a428e455b4627e0ab4ba9"}}
                      (misc/read-json (io/file target-dir "my-app.json")))))

             (testing "generates a manifest file describing the base image"
               (is (zero? (vessel/-main "image"
                                        "--repository" "openjdk"
                                        "--tag" "alpine"
                                        "--attribute" "comment:OpenJDK Alpine image"
                                        "--output" (str (io/file target-dir "openjdk-alpine.json")))))

               (is (= {:image
                       {:repository "openjdk"
                        :registry   "docker.io"
                        :tag        "alpine"
                        :comment    "OpenJDK Alpine image"}}
                      (misc/read-json (io/file target-dir "openjdk-alpine.json")))))

             (testing "generates a manifest file describing the application's
               image to be built; merges the manifests created previously"
               (is (zero? (vessel/-main "image"
                                        "--repository" "nubank/my-app"
                                        "--registry" (str registry ":5000")
                                        "--attribute" "comment:My Clojure application"
                                        "--base-image" (str (io/file target-dir "openjdk-alpine.json"))
                                        "--merge-into" (str (io/file target-dir "my-app.json"))
                                        "--output" (str (io/file target-dir "image.json")))))

               (is (match? {:image
                            {:repository "nubank/my-app"
                             :registry   (str registry ":5000")
                             :comment    "My Clojure application"
                             :tag        #"^[0-9a-f]{64}$"}
                            :base-image
                            {:image
                             {:repository "openjdk"
                              :registry   "docker.io"
                              :comment    "OpenJDK Alpine image"
                              :tag        "alpine"}}
                            :service
                            {:name       "my-app"
                             :git-commit "07dccc801700cbe28e4a428e455b4627e0ab4ba9"}}
                           (misc/read-json (io/file target-dir "image.json")))))

             (testing "containerizes the application"
               (is (zero?
                    (vessel/-main "containerize"
                                  "--app-root" "/opt/my-app"
                                  "--classpath" (classpath project-dir)
                                  "--extra-path" (str (io/file project-dir "config/config.edn") ":/etc/my-app/config.edn")
                                  "--main-class" "my-app.server"
                                  "--manifest" (str (io/file target-dir "image.json"))
                                  "--output" (str (io/file target-dir "my-app.tar"))
                                  "--preserve-file-permissions"
                                  "--source-path" (str (io/file project-dir "src"))
                                  "--resource-path" (str (io/file project-dir "resources"))
                                  "--verbose")))

               (is (true? (misc/file-exists? (io/file target-dir "my-app.tar")))))

             (testing "pushes the built image to the registry"
               (is (zero?
                    (vessel/-main "push"
                                  "--tarball" (str (io/file target-dir "my-app.tar"))
                                  "--allow-insecure-registries"
                                  "--anonymous"))))

             (testing "containerizes a new image, now using a tarball as the base image"
               (is (zero? (vessel/-main "manifest"
                                        "--attribute" (str "tar-path:" (io/file target-dir "my-app.tar"))
                                        "--object" "image"
                                        "--output" (str (io/file target-dir "tarball.json")))))

               (is (zero? (vessel/-main "image"
                                        "--repository" "nubank/my-app2"
                                        "--registry" (str registry ":5000")
                                        "--base-image" (str (io/file target-dir "tarball.json"))
                                        "--output" (str (io/file target-dir "image2.json")))))

               (is (zero?
                    (vessel/-main "containerize"
                                  "--app-root" "/opt/my-app2"
                                  "--classpath" (classpath project-dir)
                                  "--main-class" "my-app.server"
                                  "--manifest" (str (io/file target-dir "image2.json"))
                                  "--output" (str (io/file target-dir "my-app2.tar"))
                                  "--source-path" (str (io/file project-dir "src"))
                                  "--resource-path" (str (io/file project-dir "resources"))
                                  "--verbose"))))

             (testing "builds the application"
               (misc/make-empty-dir target-dir "build")
               (is (zero?
                    (vessel/-main "build"
                                  "--classpath" (classpath project-dir)
                                  "--source-path" (str (io/file project-dir "src"))
                                  "--main-class" "my-app.server"
                                  "--resource-path" (str (io/file project-dir "resources"))
                                  "--output" (str (io/file target-dir "build"))
                                  "--verbose")))

               (is (true? (misc/file-exists? (str (io/file target-dir "build"))))))))
