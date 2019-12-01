(ns packer.misc-test
  (:require [clojure.test :refer :all]
            [packer.misc :as misc])
  (:import java.io.StringWriter
           [java.time Duration Instant]
           java.util.function.Consumer))

(deftest java-consumer-test
  (is (instance? Consumer
                 (misc/java-consumer identity)))

  (is (= "Hello world!"
         (with-out-str
           (.. (misc/java-consumer #(printf %))
               (accept "Hello world!"))))))

(deftest now-test
  (is (instance? Instant (misc/now))))

(deftest duration-between-test
  (is (instance? Duration (misc/duration-between (misc/now)
                                                 (misc/now)))))

(deftest duration->string-test
  (are [duration result] (= result (misc/duration->string duration))
    (Duration/ZERO)   "0 milliseconds"
    (Duration/ofMillis 1)    "1 millisecond"
    (Duration/ofMillis 256) "256 milliseconds"
    (Duration/ofMillis 1000)         "1 second"
    (Duration/ofMillis 6537)     "6.54 seconds"
    (Duration/ofMinutes 1)         "1 minute"
    (Duration/ofMillis 63885)     "1.06 minutes"
    (Duration/ofMinutes 4)        "4 minutes"))

(deftest with-stderr-test
  (let [writer (StringWriter.)]
    (binding [*err* writer]
      (misc/with-stderr
        (print "Error!"))
      (is (= "Error!"
             (str writer))))))
