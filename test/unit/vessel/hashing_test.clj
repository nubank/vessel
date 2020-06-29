(ns vessel.hashing-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer :all]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [vessel.hashing :as hashing]))

(deftest sha256-test
  (testing "calculates hashes for arbitrary strings"
    (is (= "2df5712e1cf303a50fac51e4b2c0ac71884f746b20f206f9df2eb6375fde7270"
           (hashing/sha256 "vessel"))))

  (testing "calculates hashes for arbitrary files"
    (is (= "3fc339f64a00715c23b7c948de49d16f2f729d2707f0408e5d0ff2bac545fe2f"
           (hashing/sha256 (io/file "test/resources/hashing/file1.txt")))))

  (testing "calculates hashes for sequences and sets of strings and files"
    (are [input hash]
        (= hash (hashing/sha256 input))
      ["vessel"]                                                                                                                                    (hashing/sha256 "vessel")
      #{"vessel"}                                                                                                                                   (hashing/sha256 "vessel")
      ["hello" "vessel"]                                                                                                                            "2a46de762922ee0641bd9f8ac368bd962d422869dfb6da26b04ac5615c25275c"
      ["vessel" "hello"]                                                                                                                            (hashing/sha256 ["hello" "vessel"])
      #{"vessel" "hello"}                                                                                                                           (hashing/sha256 ["hello" "vessel"])
      [(io/file "test/resources/hashing/file1.txt")]                                                                                                (hashing/sha256 (io/file "test/resources/hashing/file1.txt"))
      [(io/file "test/resources/hashing/file1.txt") (io/file "test/resources/hashing/file2.txt")]                                                   "494d31c5dc47490a4f3f2693261e0b204069a2f217c8f417ae3119b1052cddaa"
      [(io/file "test/resources/hashing/file1.txt") (io/file "test/resources/hashing/file2.txt") (io/file "test/resources/hashing/dir1/file3.txt")] "5141749bb4aa3e496f83518083ce3a5e713fd784ff73da2e9ded39d4e07d6a13"
      #{(io/file "test/resources/hashing/file1.txt")}                                                                                               (hashing/sha256 [(io/file "test/resources/hashing/file1.txt")])))

  (testing "calculates hashes for directories by applying the hash function
  recursively for each file inside it"
    (is (=
         (hashing/sha256 [(io/file "test/resources/hashing/file1.txt")
                          (io/file "test/resources/hashing/file2.txt")
                          (io/file "test/resources/hashing/dir1/file3.txt")])
         (hashing/sha256 (io/file "test/resources/hashing"))))))

(defspec sha256-spec-test
  {:num-tests 25}
  (prop/for-all [input-str gen/string
                 input-seq (gen/vector gen/string)]
                (is (= (hashing/sha256 input-str)
                       (hashing/sha256 input-str))
                    "two identical strings always produce the same hash")

                (is (= (hashing/sha256 input-seq)
                       (hashing/sha256 input-seq))
                    "two identical vectors always produce the same hash")

                (is (= (hashing/sha256 (distinct input-seq))
                       (hashing/sha256 (set input-seq)))
                    "identical vectors and sets with distinct elements always
                    produce the same hashes")))
