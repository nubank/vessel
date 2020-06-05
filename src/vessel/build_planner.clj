(ns vessel.build-planner
  (:require [clojure.java.io :as io])
  (:import [java.io ByteArrayOutputStream File]
           java.security.MessageDigest))

(defn- hex
  "Returns the hexadecimal representation of the provided array of
  bytes."
  ^String
  [bytes]
  (let [builder (StringBuilder.)]
    (run! #(.append builder (format "%02x" %)) bytes)
    (str builder)))

(defn- get-bytes
  "Given a java.io.File object, returns its content as an array of
  bytes."
  [^File file]
  (let [output (ByteArrayOutputStream.)]
    (io/copy (io/input-stream file) output)
    (.toByteArray output)))

(defn- sha-256
  "Returns the SHA-256 digest for the byte array in question."
  ^String
  [input]
  (let [message-digest (MessageDigest/getInstance "SHA-256")]
    (.update message-digest input)
    (hex (.digest message-digest))))

(defprotocol Checksum
  (sha256-sum [input]))

(extend-protocol Checksum
  String
  (sha256-sum [^String input]
    (sha-256 (.getBytes input)))

  File
  (sha256-sum [^File input]
    (if (.isFile input)
      (sha-256 (get-bytes input))
      (->> (file-seq input)
           (filter #(.isFile %))
           (map sha256-sum)
           sort
           (apply str)
           sha256-sum))))
