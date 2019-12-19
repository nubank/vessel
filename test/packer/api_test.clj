(ns packer.api-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer :all]
            [packer.api :as api]))

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
