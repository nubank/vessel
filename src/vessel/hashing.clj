(ns vessel.hashing
  (:require [clojure.java.io :as io])
  (:import [clojure.lang IPersistentSet Sequential]
           [java.io ByteArrayOutputStream File]
           java.security.MessageDigest))

(defn- hex
  "Returns the hexadecimal representation of the provided array of
  bytes."
  ^String
  [bytes]
  (let [builder (StringBuilder.)]
    (run! #(.append builder (format "%02x" %)) bytes)
    (str builder)))

(defn-   ^String sha256-sum
  "Returns the SHA256 checksum for the byte array in question."
  [input]
  (let [message-digest (MessageDigest/getInstance "SHA-256")]
    (.update message-digest input)
    (hex (.digest message-digest))))

(defn- get-bytes
  "Given a java.io.File object, returns its content as an array of
  bytes."
  [^File file]
  (let [output (ByteArrayOutputStream.)]
    (io/copy (io/input-stream file) output)
    (.toByteArray output)))

(defprotocol Hashing
  "Calculates hashes for different types of objects and data structures."
  (sha256 [input]
    "Returns the sha256 digest for the supplied input."))

(extend-protocol Hashing
  String
  (sha256 [^String input]
    (sha256-sum (.getBytes input)))

  Sequential
  (sha256 [^ISeq input]
    (if (= 1 (count input))
      (sha256 (first input))
      (->> input
           (map sha256)
           sort
           (apply str)
           sha256)))

  IPersistentSet
  (sha256 [^IPersistentSet input]
    (sha256 (vec input)))

  File
  (sha256 [^File input]
    (if (.isFile input)
      (sha256-sum (get-bytes input))
      (->> (file-seq input)
           (filter #(.isFile %))
           sha256))))
