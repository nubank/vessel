(ns vessel.program-integration-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer :all]
            [mockfn.macros :refer [calling providing]]
            [vessel.misc :as misc]
            [vessel.program :as vessel]
            [vessel.test-helpers :refer [classpath ensure-clean-test-dir]]))

(use-fixtures :once (ensure-clean-test-dir))

(def project-dir (io/file "test/resources/my-app"))

(def target-dir (io/file "target/tests/program-integration-test"))

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
                                        "--registry" "localhost:5000"
                                        "--attribute" "comment:My Clojure application"
                                        "--base-image" (str (io/file target-dir "openjdk-alpine.json"))
                                        "--merge-into" (str (io/file target-dir "my-app.json"))
                                        "--output" (str (io/file target-dir "image.json")))))

               (is (= {:image
                       {:repository "nubank/my-app"
                        :registry   "localhost:5000"
                        :comment    "My Clojure application"
                        :tag        "71237936c573fd34cde3a0dea637149be73a5323c7dbe16e1b119d36f51cffe4"}
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
                                  "--anonymous"))))))
