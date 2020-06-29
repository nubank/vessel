(ns vessel.container-builder-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer :all]
            [matcher-combinators.matchers :as m]
            [matcher-combinators.test :refer [match?]]
            [mockfn.macros :refer [providing]]
            [vessel.clojure.classpath :as clojure.classpath]
            [vessel.container-builder :as container-builder]
            [vessel.misc :as misc]
            [vessel.test-helpers :refer [ensure-clean-test-dir read-from-tarball]]))

(use-fixtures :once (ensure-clean-test-dir))

(deftest resolve-deps-test
  (let [workspace   (io/file "/workspace")
        deps-edn    (io/file workspace "deps.edn")
        project-clj (io/file workspace "project.clj")]
    (providing [(clojure.classpath/assemble-deps {:clojure/tool :tools.deps.alpha :project/descriptor deps-edn}) [(io/file "clojure-1.10.1.jar")]
                (clojure.classpath/assemble-deps {:clojure/tool :leiningen :project/descriptor project-clj}) [(io/file "clojure-1.10.0.jar")]]
               (testing "resolves dependencies declared in the deps.edn file"
                 (providing [(misc/file-exists? deps-edn) true]
                            (is (= [(io/file "clojure-1.10.1.jar")]
                                   (#'container-builder/resolve-deps workspace)))))

               (testing "resolves dependencies declared in the project.clj file"
                 (providing [(misc/file-exists? deps-edn) false
                             (misc/file-exists? project-clj) true]
                            (is (= [(io/file "clojure-1.10.0.jar")]
                                   (#'container-builder/resolve-deps workspace)))))

               (testing "throws an exception when there are no project descriptor files in the provided directory"
                 (providing [(misc/file-exists? deps-edn) false
                             (misc/file-exists? project-clj) false]
                            (is (thrown? java.io.FileNotFoundException
                                         (#'container-builder/resolve-deps workspace))))))))

(deftest classify-deps-test
  (let [dep-1   (io/file "nubank/dep-1.jar")
        dep-2   (io/file "nubank/dep-2.jar")
        clojure (io/file "clojure/clojure-1.10.1.jar")
        deps    [dep-1 dep-2 clojure]]
    (are [classifiers deps result]
        (= result (#'container-builder/classify-deps classifiers deps))
      nil                       deps {:application-deps (set deps)}
      {}                        deps {:application-deps (set deps)}
      {:nubank-libs #"nubank"}  deps {:nubank-libs #{dep-1 dep-2} :other-deps #{clojure}}
      {:a #"dep-1" :b #"dep-2"} deps {:a #{dep-1} :b #{dep-2} :other-deps #{clojure}})))

(def cache-dir (misc/make-dir (misc/home-dir) (io/file ".vessel-cache")))

(def project-dir (io/file "test/resources/my-app"))

(def test-dir (io/file "target/tests/container-builder-test"))

(def tar-path-1 (io/file test-dir "my-app-1.tar"))

(def tar-path-2 (io/file test-dir "my-app-2.tar"))

(def options {:cache-dir            cache-dir
              :digest-path          (io/file test-dir "digest.txt")
              :image-reference-path (io/file test-dir "image-reference.txt")
              :project-dir          project-dir
              })

(def manifest
  #:vessel.v1
  {:id             "a0b4444937703a939a5b00cad0f638e4bd99d0ab783a02aca2d2f5c8cedb8181"
   :from           "openjdk@sha256:1fd5a77d82536c88486e526da26ae79b6cd8a14006eb3da3a25eb8d2d682ccd6"
   :target         "nubank/my-app:v1"
   :app-root       (io/file "/my-app")
   :app-type       :war
   :main-ns        'my-app.server
   :source-paths   #{(io/file project-dir "src")}
   :resource-paths #{(io/file project-dir "resources")}
   :extra-paths
   [{:from (io/file project-dir "config/config.edn")
     :to (io/file "/etc/my-app/config.edn")}]})

(def sha256-re #"^sha256:[a-f0-9]{64}$")

(deftest apply-build-plan-test
  (let [build-plan (container-builder/make-build-plan manifest options)]

    (testing "containerizes the application in question, updates the extended
  cache and writes files containing container metadata to specified paths"
      (container-builder/apply-build-plan build-plan
                                          (assoc options :tar-path tar-path-1))

      (is (misc/file-exists? tar-path-1)
          "the tarball has been created")

      (is (match? {:history
                   (m/embeds [{:comment "source-paths"}
                              {:comment "resource-paths"}
                              {:comment "application-deps"}
                              {:comment "extra-paths"}])}
                  (misc/read-json (read-from-tarball tar-path-1 "config.json")))
          "makes sure that the container configuration contains the expected layers")

      (is (match?
           (m/equals            {:source-paths
                                 #:layer {:digest sha256-re
                                          :hash
                                          "65756e201d0aa6baa42a4a75962d55f35fa9b8df9a647e9c5d2a7dee22ff7a24"}
                                 :resource-paths
                                 #:layer {:digest sha256-re
                                          :hash
                                          "d17330b4fa82a94a7a66af29ea218e42769fcd61cb6a7df59f6aafccccbeb89f"}
                                 :application-deps
                                 #:layer {:digest sha256-re
                                          :hash
                                          "8c6603f6723405eaf90a870d346cf607b5bf879a315cbaebe37695df8e13a99c"}})
           (misc/read-edn (io/file cache-dir "indexes/registry-1.docker.io/nubank/my-app/a0b4444937703a939a5b00cad0f638e4bd99d0ab783a02aca2d2f5c8cedb8181.edn")))
          "the correct index file has been created at the indexes directory
          within the cache")

      (is (re-find #"^sha256:[a-f0-9]{64}$"
                   (slurp (:digest-path options)))
          "a valid digest has been written at the specified path")

      (is (re-find #"^nubank/my-app@sha256:[a-f0-9]{64}$"
                   (slurp (:image-reference-path options)))
          "the qualified image reference has been written at the specified path"))

    (testing "containerizes the application again and generates the same layers as before"
      (container-builder/apply-build-plan build-plan
                                          (assoc options :tar-path tar-path-2))

      (is (misc/file-exists? tar-path-2)
          "the tarball has been created")

      (is (match? (m/in-any-order
                   (:Layers (first (misc/read-json (read-from-tarball tar-path-1 "manifest.json")))))
                  (:Layers (first (misc/read-json (read-from-tarball tar-path-2 "manifest.json")))))
          "guarantees that subsequent builds whit no changes are deterministic")

      (is (match? {:history
                   (m/embeds [{:comment "source-paths"}
                              {:comment "resource-paths"}
                              {:comment "application-deps"}
                              {:comment "extra-paths"}])}
                  (misc/read-json (read-from-tarball tar-path-2 "config.json")))
          "makes sure that the container configuration contains the expected layers"))))
