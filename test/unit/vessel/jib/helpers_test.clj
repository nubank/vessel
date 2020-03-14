(ns vessel.jib.helpers-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer :all]
            [matcher-combinators.matchers :as m]
            [matcher-combinators.test :refer [match?]]
            [vessel.jib.helpers :as jib.helpers]
            [vessel.misc :as misc]
            [vessel.test-helpers :refer [ensure-clean-test-dir]])
  (:import com.google.cloud.tools.jib.api.AbsoluteUnixPath))

(use-fixtures :once (ensure-clean-test-dir))

(deftest string->absolute-unix-path-test
  (is (instance? AbsoluteUnixPath (jib.helpers/string->absolute-unix-path "/"))))

(deftest extract-tarball-test
  (let [tarball     (io/file "test/resources/fake-app.tar")
        destination (io/file "target/tests/helpers-test")]
    (jib.helpers/extract-tarball tarball destination)
    (is (match? (m/in-any-order ["030a57e84b5be8d31b3c061ff7d7653836673f50475be0a507188ced9d0763d1.tar.gz"
                                 "051334be9afdd6a54c28ef9f063d2cddf7dbf79fcc9b1b0965cb1f69403db6b5.tar.gz"
                                 "config.json"
                                 "manifest.json"])
                (map #(.getName %) (misc/filter-files (file-seq destination)))))))
