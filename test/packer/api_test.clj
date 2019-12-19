(ns packer.api-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer :all]
            [matcher-combinators.test :refer [match?]]
            [packer.api :as api]
            [packer.misc :as misc]))

(def my-app-manifest (io/file "target/my-app.json"))

(use-fixtures :each
  (fn [f]
    (io/make-parents my-app-manifest)
    (f)
    (.delete my-app-manifest)))

(deftest manifest-test
  (testing "generates a manifest by writing it to the stdout or the specified
  file"
    (let [expected-manifest "{\"service\":{\"name\":\"my-app\",\"version\":\"v1\"}}\n"]
      (is (= expected-manifest
             (with-out-str
               (api/manifest {:attributes [[:name "my-app"]
                                           [:version "v1"]]
                              :object :service
                              :output *out*}))))

      (is (= expected-manifest
             (do (api/manifest {:attributes [[:name "my-app"]
                                             [:version "v1"]]
                                :object :service
                                :output (io/writer (io/file "target/my-app.json"))})
                 (slurp my-app-manifest)))))))

(deftest image-test
  (let [base-image {:image
                    {:registry "docker.io"
                     :repository "openjdk"
                     :tag "alpine"}}
        data {:registry "my-registry.com"
              :repository "my-app"}]
    (testing "generates an image manifest"
      (is (= {:image {:registry "my-registry.com"
                      :repository "my-app"
                      :tag "9965bb9aad0efdaf499a35368f338ea053689e8d44cadb748991a84fd1eb355d"}}
             (do (api/image (assoc data               :output (io/writer my-app-manifest)))
                 (misc/read-json my-app-manifest))))

      (is (match? {:base-image base-image
                   :image {:registry "my-registry.com"
                           :repository "my-app"
                           :tag string?}}
                  (do (api/image (assoc data :base-image base-image
                                        :output (io/writer my-app-manifest)))
                      (misc/read-json my-app-manifest)))
          "assoc's the base image's manifest")

      (is (match? {:image {:registry "my-registry.com"
                           :repository "my-app"
                           :tag string?
                           :git-commit "4c52b901c6"}}
                  (do (api/image (assoc data :attributes #{[:git-commit "4c52b901c6"]}
                                        :output (io/writer my-app-manifest)))
                      (misc/read-json my-app-manifest)))
          "assoc's arbitrary attributes into the resulting manifest")

      (is (match? {:image {:registry "my-registry.com"
                           :repository "my-app"
                           :tag string?}
                   :service {:name "my-service"
                             :type "clojure"}}
                  (do (api/image (assoc data :manifests #{{:service {:name "my-service"
                                                                     :type "clojure"}}}
                                        :output (io/writer my-app-manifest)))
                      (misc/read-json my-app-manifest)))
          "merges arbitrary manifests"))))
