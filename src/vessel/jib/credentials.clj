(ns vessel.jib.credentials
  (:require [clojure.string :as string]
            [cognitect.aws.client.api :as aws]
            [vessel.jib.helpers :as jib.helpers])
  (:import [com.google.cloud.tools.jib.api Credential CredentialRetriever ImageReference]
           com.google.cloud.tools.jib.frontend.CredentialRetrieverFactory
           com.google.cloud.tools.jib.registry.credentials.CredentialRetrievalException
           [java.util Base64 Base64$Decoder Optional]))

(def ^:private ecr-url #"^(\d+)\.dkr.ecr\.([^\.]+)\..*")

(defn- account-id-and-region
  "Given an ImageReference object, returns the Amazon account id and the
  region where the registry lives."
  [^ImageReference image-reference]
  (let [registry (.getRegistry image-reference)]
    (some->> registry
             (re-matches ecr-url)
             rest
             (zipmap [:account-id :region]))))

(defn- check-authentication-error
  "Throws a CredentialRetrievalException when the response returned by
  aws-api contains Cognitect anomalies."
  [response]
  (if-not (:cognitect.anomalies/category response)
    response
    (throw (CredentialRetrievalException.
            (ex-info "Unable to authenticate on Amazon ECR" response)))))

(defn- get-ecr-authorization-token
  "Given an ImageReference object, calls the ECR API to obtain an
  authorization token that, presumably, grants access to pull and/or
  push images from/to the registry in question."
  [^ImageReference image-reference]
  (let [{:keys [account-id region]} (account-id-and-region image-reference)
        client                      (aws/client {:api    :ecr
                                                 :region region})]
    (-> (aws/invoke client {:op      :GetAuthorizationToken
                            :request {:registryIds [account-id]}})
        check-authentication-error
        :authorizationData
        first
        :authorizationToken)))

(defn get-ecr-credential
  "Given an ImageReference object, calls the ECR API and returns a map
  containing the keys :username and :password representing a
  credential to access the ECR registry in question."
  [^ImageReference image-reference]
  (let [^Base64$Decoder decoder       (Base64/getDecoder)
        ^String username-and-password (->> (get-ecr-authorization-token image-reference)
                                           (.decode decoder)
                                           (String.))]
    (zipmap [:username :password] (string/split username-and-password #":"))))

(defn- ecr-registry?
  "True if the ImageReference is associated to an ECR registry."
  [^ImageReference image-reference]
  (re-find ecr-url (.getRegistry image-reference)))

(defn ^CredentialRetriever ecr-credential-retriever
  "Returns an instance of Credentialretriever interface that attempts to
  retrieve credential from Amazon Elastic Container Registry.

  If the supplied ImageReference isn't related to ECR, returns
  Optional/empty. Otherwise, attempts to retrieve the credential by
  calling the ECR API. Credentials to interact with Amazon API are
  obtained through the same chain supported by awscli or AWS SDK."
  [^ImageReference image-reference]
  (reify CredentialRetriever
    (retrieve [this]
      (if-not (ecr-registry? image-reference)
        (Optional/empty)
        (let [{:keys [username password]} (get-ecr-credential image-reference)]
          (Optional/of (Credential/from username password)))))))

(defn ^CredentialRetriever docker-config-retriever
  "Returns an instance of the CredentialRetriever interface that
  retrieves credentials from the Docker config."
  [^ImageReference image-reference]
  (.. CredentialRetrieverFactory
      (forImage image-reference (jib.helpers/log-event-handler "vessel.jib.helpers"))
      dockerConfig))

(def ^:private default-retrievers
  [ecr-credential-retriever
   docker-config-retriever])

(defn ^CredentialRetriever retriever-chain
  "Returns an instance of Credentialretriever interface that attempts to
  retrieve credentials to deal with the supplied image by calling a
  chain of credential retrievers.

  The argument retrievers is a sequence of 1-arity functions that take
  an ImageReference and returns a CredentialRetriever. If omitted, the
  default chain (ECR and Docker config) will be assumed"
  ([^ImageReference image-reference]
   (retriever-chain image-reference default-retrievers))
  ([^ImageReference image-reference retrievers]
   (reify CredentialRetriever
     (retrieve [this]
       (or (some (fn [retriever]
                   (let [^Credential credential (.. (retriever  image-reference) retrieve)]
                     (when (.isPresent credential)
                       credential)))
                 retrievers)
           (Optional/empty))))))
