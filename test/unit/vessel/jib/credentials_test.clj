(ns vessel.jib.credentials-test
  (:require [clojure.test :refer :all]
            [cognitect.aws.client.api :as aws]
            [matcher-combinators.standalone :as standalone]
            [mockfn.macros :refer [providing]]
            [vessel.jib.credentials :as credentials])
  (:import [com.google.cloud.tools.jib.api Credential CredentialRetriever ImageReference]
           com.google.cloud.tools.jib.registry.credentials.CredentialRetrievalException
           java.util.Optional))

(deftest get-ecr-credential-test
  (providing [(aws/client (standalone/match? {:api    :ecr
                                              :region "us-east-1"})) 'client]

             (testing "returns username and password to access the ECR registry that the
  image in question is associated to"
               (providing [(aws/invoke 'client {:op      :GetAuthorizationToken
                                                :request {:registryIds ["591385309914"]}})
                           {:authorizationData [{:authorizationToken "QVdTOnBhc3N3b3Jk"}]}]
                          (is (= {:username "AWS"
                                  :password "password"}
                                 (credentials/get-ecr-credential
                                  (ImageReference/parse "591385309914.dkr.ecr.us-east-1.amazonaws.com/application:v1.0.1"))))))

             (testing "throws a CredentialRetrievalException when the
             authentication on ECR fails"
               (providing [(aws/invoke 'client {:op      :GetAuthorizationToken
                                                :request {:registryIds ["591385309914"]}})
                           {:cognitect.anomalies/category :cognitect.anomalies/incorrect}]
                          (is (thrown? CredentialRetrievalException
                                       (credentials/get-ecr-credential
                                        (ImageReference/parse "591385309914.dkr.ecr.us-east-1.amazonaws.com/application:v1.0.1"))))))))

(deftest ecr-credential-retriever-test
  (testing "returns the credential to access Amazon ECR"
    (let [image (ImageReference/parse "591385309914.dkr.ecr.us-east-1.amazonaws.com/application:v1.0.1")]
      (providing [(credentials/get-ecr-credential image) {:username "AWS" :password "password"}]
                 (let [retriever  (credentials/ecr-credential-retriever image)
                       credential (.. retriever retrieve get)]
                   (is (= "AWS"
                          (.getUsername credential)))
                   (is (= "password"
                          (.getPassword credential)))))))

  (testing "returns Optional/empty when the image isn't associated to an ECR registry"
    (let [image (ImageReference/parse "docker.io/repo/application:v1.0.1")]
      (let [retriever (credentials/ecr-credential-retriever image)]
        (is (false? (.. retriever retrieve isPresent)))))))

(defn make-retriever
  ([]
   (fn [_]
     (reify CredentialRetriever
       (retrieve [this]
         (Optional/empty)))))
  ([username password]
   (fn [_]
     (reify CredentialRetriever
       (retrieve [this]
         (Optional/of (Credential/from username password)))))))

(deftest retriever-chain-test
  (testing "returns the first non-empty credential retrieved by the supplied
  retrievers"
    (let [get-credentials (fn [credentials]
                            [(.getUsername credentials) (.getPassword credentials)])]
      (are [retrievers username password] (= [username password]
                                             (get-credentials (.. (credentials/retriever-chain (ImageReference/parse "repo/application:v1.0.1") retrievers) retrieve get)))
        [(make-retriever "john.doe" "abc123")]                                "john.doe" "abc123"
        [(make-retriever "john.doe" "abc123") (make-retriever "jd" "def456")] "john.doe" "abc123"
        [(make-retriever "jd" "def456") (make-retriever "john.doe" "abc123")] "jd"       "def456"
        [(make-retriever) (make-retriever "john.doe" "abc123")]               "john.doe" "abc123"
        [(make-retriever "john.doe" "abc123") (make-retriever)]               "john.doe" "abc123")))

  (testing "returns Optional/empty when all retrievers return empty credentials"
    (is (= (Optional/empty)
           (.. (credentials/retriever-chain (ImageReference/parse "repo/application:v1.0.1") [(make-retriever) (make-retriever)])
               retrieve)))))
