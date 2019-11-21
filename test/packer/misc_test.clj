(ns packer.misc-test
  (:require [clojure.test :refer :all]
            [packer.misc :as misc])
  (:import java.util.function.Consumer))

(deftest java-consumer-test
  (is (instance? Consumer
                 (misc/java-consumer identity)))

  (is (= "Hello world!"
         (with-out-str
           (.. (misc/java-consumer #(printf %))
               (accept "Hello world!"))))))
