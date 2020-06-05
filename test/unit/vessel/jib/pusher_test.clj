(ns vessel.jib.pusher-test
  (:require [clojure.core.async :as async]
            [clojure.java.io :as io]
            [clojure.test :refer :all]
            [mockfn.macros :refer [calling providing verifying]]
            [mockfn.matchers :refer [a any exactly pred]]
            [vessel.jib.credentials :as jib.credentials]
            [vessel.jib.pusher :as pusher]
            [vessel.test-helpers :refer [ensure-clean-test-dir]])
  (:import [com.google.cloud.tools.jib.api Credential CredentialRetriever DescriptorDigest ImageReference]
           com.google.cloud.tools.jib.blob.BlobDescriptor
           com.google.cloud.tools.jib.image.json.BuildableManifestTemplate
           com.google.cloud.tools.jib.registry.RegistryClient
           java.util.Optional))

(use-fixtures :once (ensure-clean-test-dir))

(def credential-retriever
  (reify CredentialRetriever
    (retrieve [this]
      (Optional/of (Credential/from "user" "password")))))

(deftest make-registry-client-test
  (testing "by default, attempts to retrieve credentials and to authenticate on
  the registry"
    (verifying [(jib.credentials/retriever-chain (a ImageReference)) credential-retriever (exactly 1)
                (pusher/authenticate (a RegistryClient)) any (exactly 1)]
               (is (instance? RegistryClient
                              (pusher/make-registry-client (ImageReference/parse "library/my-app:v1") {})))))

  (testing "when :anonymous? is set to true, neither attempts to retrieve
  credentials nor to authenticate on the registry"
    (verifying [(jib.credentials/retriever-chain (a ImageReference)) any (exactly 0)
                (pusher/authenticate (a RegistryClient)) any (exactly 0)]
               (is (instance? RegistryClient
                              (pusher/make-registry-client (ImageReference/parse "library/my-app:v1") {:anonymous? true}))))))

(defn closed-channel []
  (let [channel (async/chan)]
    (async/close! channel)
    channel))

(deftest push-layer-test
  (let [^DescriptorDigest digest   (DescriptorDigest/fromDigest "sha256:8e3ba11ec2a2b39ab372c60c16b421536e50e5ce64a0bc81765c2e38381bcff6")
        ^BlobDescriptor descriptor (BlobDescriptor. digest)
        blob-data                  #:blob {:descriptor descriptor :reader (constantly nil)}
        channel                    (closed-channel)]
    (testing "when the layer already exists on the registry, skips the push"
      (providing [(#'pusher/check-blob 'client digest) true]
                 (verifying [(#'pusher/push-blob 'client channel blob-data) any (exactly 0)]
                            (is (any?
                                 (pusher/push-layer 'client channel blob-data))))))

    (testing "when the layer doesn't exist on the registry, pushes it"
      (providing [(#'pusher/check-blob 'client digest) false]
                 (verifying [(#'pusher/push-blob 'client channel blob-data) any (exactly 1)]
                            (is (any?
                                 (pusher/push-layer 'client channel blob-data))))))

    (testing "returns a Throwable object when the push throws an exception"
      (providing [(#'pusher/check-blob 'client digest) (calling (fn [_ _]
                                                                  (throw (Exception. "Boom!"))))]
                 (is (instance? Throwable
                                (pusher/push-layer 'client channel blob-data)))))))

(defn manifest-of-digest
  [^DescriptorDigest digest]
  (pred (fn [^BuildableManifestTemplate manifest]
          (= digest
             (.. manifest getContainerConfiguration getDigest)))))

(deftest push-test
  (let [^DescriptorDigest layer1-digest (DescriptorDigest/fromDigest "sha256:030a57e84b5be8d31b3c061ff7d7653836673f50475be0a507188ced9d0763d1")
        ^DescriptorDigest layer2-digest (DescriptorDigest/fromDigest "sha256:051334be9afdd6a54c28ef9f063d2cddf7dbf79fcc9b1b0965cb1f69403db6b5")
        tarball                         (io/file "test/resources/fake-app.tar")
        temp-dir                        (io/file "target/tests/pusher-test")]

    (testing "ensures that all steps needed to push an image are being performed
  accordingly"
      (let [^DescriptorDigest image-digest (DescriptorDigest/fromDigest "sha256:ca3d163bab055381827226140568f3bef7eaac187cebd76878e0b63e9e442356")]
        (providing [(jib.credentials/retriever-chain (a ImageReference)) credential-retriever
                    (pusher/authenticate (a RegistryClient)) any
                    (#'pusher/check-blob (a RegistryClient) layer1-digest) true
                    (#'pusher/check-blob (a RegistryClient) layer2-digest) true
                    ;; Called from push-container-config
                    (#'pusher/push-blob (a RegistryClient) (any) (pred map?)) any
                    (pusher/push-manifest (a RegistryClient) (any) (manifest-of-digest image-digest) (Optional/of "v1")) image-digest]
                   (is (any?
                        (pusher/push {:tarball  tarball
                                      :temp-dir temp-dir}))))))

    (testing "throws an exception when one of the layers can't be pushed"
      (providing [(jib.credentials/retriever-chain (a ImageReference)) credential-retriever
                  (pusher/authenticate (a RegistryClient)) any
                  (#'pusher/check-blob (a RegistryClient) layer1-digest) true
                  (#'pusher/check-blob (a RegistryClient) layer2-digest) (calling (fn [_ _]
                                                                                    (throw (Exception. "Unknown error"))))]
                 (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                       #"One or more layers could not be pushed into remote registry"
                                       (pusher/push {:tarball  tarball
                                                     :temp-dir temp-dir})))))))
