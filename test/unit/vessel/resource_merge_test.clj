(ns vessel.resource-merge-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [vessel.resource-merge :as merge]))

(deftest find-matching-rule-test
  (let [data-readers-file (io/file "test/resources/lib1/data_readers.clj")
        edn-file          (io/file "test/resources/lib1/resource1.edn")]
    (testing "if rules are empty, it returns nil"
      (is (nil? (merge/find-matching-rule [] edn-file))))

    (testing "if no rules match, it returns nil"
      (let [rule {:match-fn (constantly false)
                  :read-fn  (constantly "rule")
                  :merge-fn (constantly "rule")
                  :write-fn (constantly "rule")}]
        (is (nil? (merge/find-matching-rule [rule] edn-file)))))

    (testing "if multiple rules match, it returns the first one"
      (let [first-rule  {:match-fn (constantly true)
                         :read-fn  (constantly "first-rule")
                         :merge-fn (constantly "first-rule")
                         :write-fn (constantly "first-rule")}
            second-rule {:match-fn (constantly true)
                         :read-fn  (constantly "second-rule")
                         :merge-fn (constantly "second-rule")
                         :write-fn (constantly "second-rule")}]
        (is (= first-rule
               (merge/find-matching-rule [first-rule second-rule] edn-file)))))

    (testing "with base-rules"
      (testing "if file matches data_readers pattern, it returns the data-readers-base-rule"
        (is (= merge/data-readers-base-rule
               (merge/find-matching-rule merge/base-rules data-readers-file))))

      (testing "if file ends with .edn, it returns the edn-base-rule"
        (is (= merge/edn-base-rule
               (merge/find-matching-rule merge/base-rules edn-file)))))))
