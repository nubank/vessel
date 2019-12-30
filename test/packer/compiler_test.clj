(ns packer.compiler-test
  (:require [packer.compiler :as compiler]
            [clojure.test :refer :all]
            [clojure.java.io :as io]))

(deftest class-file->ns-test
  (are [file-path ns] (= ns (compiler/class-file->ns (io/file file-path)))
    "clj_jwt/base64$decode.class" 'clj-jwt.base64
    "clj_jwt/base64/ByteArrayInput.class" 'clj-jwt.base64.ByteArrayInput
    "clj_tuple$fn__18034.class" 'clj-tuple
    "zookeeper$host_acl.class" 'zookeeper
    "zookeeper$set_data$fn__54225.class" 'zookeeper
    "zookeeper__init.class" 'zookeeper))
