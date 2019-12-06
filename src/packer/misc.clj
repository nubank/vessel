(ns packer.misc
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as string])
  (:import clojure.lang.Sequential
           com.google.cloud.tools.jib.api.AbsoluteUnixPath
           java.io.File
           [java.nio.file Path Paths]
           java.security.MessageDigest
           java.text.DecimalFormat
           [java.time Duration Instant]
           [java.util ArrayList Locale]
           java.util.function.Consumer))

(defn sequential->java-list ^ArrayList
  [^Sequential seq]
  (ArrayList. seq))

(defn string->java-path ^Path
  [^String path]
  (Paths/get path (into-array String [])))

(defn string->absolute-unix-path ^AbsoluteUnixPath
  [^String path]
  (AbsoluteUnixPath/get path))

(defn java-consumer
  "Returns a java.util.function.Consumer instance that calls the function f.

  f is a 1-arity function that returns nothing."
  ^Consumer
  [f]
  (reify Consumer
    (accept [_ arg]
      (f arg))))

(defn now
  "Return a java.time.Instant object representing the current instant."
  []
  (Instant/now))

(defn duration-between
  "Return a java.time.Duration object representing the duration between two temporal objects."
  [start end]
  (Duration/between start end))

(def ^:private formatter
  "Instance of java.text.Decimalformat used internally to format decimal
  values."
  (let [decimal-format (DecimalFormat/getInstance (Locale/ENGLISH))]
    (.applyPattern decimal-format "#.##")
    decimal-format))

(defn- format-duration
  [value time-unit]
  (str (.format formatter value) " "
       (if (= (float value) 1.0)
         (name time-unit)
         (str (name time-unit) "s"))))

(defn duration->string
  "Returns a friendly representation of the duration object in question."
  [^Duration duration]
  (let [millis (.toMillis duration)]
    (cond
      (<= millis 999)   (format-duration millis :millisecond)
      (<= millis 59999) (format-duration (float (/ millis 1000)) :second)
      :else             (format-duration (float (/ millis 60000)) :minute))))

(defmacro with-stderr
  "Binds *out* to *err* and evaluates body."
  [& body]
  `(binding [*out* *err*]
     ~@body))

(defn log
  [level emitter message & args]
  (printf "%s [%s] %s%n"
          (string/upper-case (if (keyword? level)
                               (name level)
                               (str level)))
          emitter
          (apply format message args)))

(defn find-files-at
  [^File dir]
  (.listFiles dir))

(defn file-exists?
  "Returns true if the file exists or false otherwise."
  [^File file]
  (.exists file))

(defmacro with-clean-dir
  "binding => [binding-symbol binding-value]
  binding-value => java.io.File

  Evaluates body in a context where the directory assigned to
  binding-symbol exists and is an empty directory."
  [binding & body]
  {:pre [(vector? binding) (= 2 (count binding))]}
  (let [[binding-symbol binding-value] binding]
    `(let [^File ~binding-symbol ~binding-value]
       (when (file-exists? ~binding-symbol)
         (run! #(io/delete-file %) (reverse (file-seq ~binding-symbol))))
       (.mkdir ~binding-symbol)
       ~@body)))

(defn read-json
  "Reads a JSON object and parses it as Clojure data.

  input can be any object supported by clojure.core/slurp."
  [input]
  (json/read-str (slurp input) :key-fn keyword))

(defn- hex
  "Returns the hexadecimal representation of the provided array of
  bytes."
  ^String
  [bytes]
  (let [builder (StringBuilder.)]
    (run! #(.append builder (format "%02x" %)) bytes)
    (str builder)))

(defn sha-256
  "Returns the SHA-256 digest for the Clojure object in question."
  ^String
  [data]
  (let [message-digest (MessageDigest/getInstance "SHA-256")
        input (.getBytes (pr-str data) "UTF-8")]
    (.update message-digest input)
    (hex (.digest message-digest))))
