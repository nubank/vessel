(ns vessel.misc
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as string])
  (:import java.io.File
           [java.nio.file Files LinkOption Path Paths]
           java.security.MessageDigest
           java.text.DecimalFormat
           (java.nio.file.attribute FileTime)
           [java.time Duration Instant]
           java.util.function.Consumer
           java.util.Locale))

(defn assoc-some
  "Assoc's key and value into the associative data structure only when
  the value isn't nil."
  [m & kvs]
  {:pre [(even? (count kvs))]}
  (reduce (fn [result [key val]]
            (if-not (nil? val)
              (assoc result key val)
              result))
          m (partition 2 kvs)))

(def kebab-case
  "Converts a string in camel or snake case to kebap case."
  (comp string/lower-case #(string/replace % #"([a-z])([A-Z])|_+" "$1-$2")))

;; Java interop functions

(defn  ^Path string->java-path
  [^String path]
  (Paths/get path (into-array String [])))

(defn   ^Consumer java-consumer
  "Returns a java.util.function.Consumer instance that calls the function f.

  f is a 1-arity function that returns nothing."
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

(defn ^String duration->string
  "Returns a friendly representation of the duration object in question."
  [^Duration duration]
  (let [millis (.toMillis duration)]
    (cond
      (<= millis 999)   (format-duration millis :millisecond)
      (<= millis 59999) (format-duration (float (/ millis 1000)) :second)
      :else             (format-duration (float (/ millis 60000)) :minute))))

;; I/O functions.

(defmacro with-stderr
  "Binds *out* to *err* and evaluates body."
  [& body]
  `(binding [*out* *err*]
     ~@body))

(def ^:dynamic *verbose-logs* false)

(defn- emit-log
  [level emitter message]
  (cond
    *verbose-logs*                    (println (format "%s [%s] %s" level emitter message))
    (#{"ERROR" "FATAL" "INFO"} level) (println message)))

(defn log*
  [level emitter message & args]
  (let [the-level   (string/upper-case (if (keyword? level)
                                         (name level) (str level)))
        the-message (apply format message args)]
    (if (#{"ERROR" "FATAL"} the-level)
      (with-stderr (emit-log the-level emitter the-message))
      (emit-log the-level emitter the-message))))

(defmacro log
  [level message & args]
  `(apply log*
          ~level ~(str (ns-name *ns*)) ~message
          [~@args]))

(defn file-exists?
  "Returns true if the file exists or false otherwise."
  [^File file]
  (.exists file))

(defn filter-files
  "Given a sequence of java.io.File objects (either files or
  directories), returns just the files."
  [fs]
  (filter #(.isFile %) fs))

(defn ^File home-dir
  "Returns a file object representing the home directory of the current
  user."
  []
  (io/file (System/getProperty "user.home")))

(defn ^Instant last-modified-time
  "Returns the file's last modified time as a java.time.Instant."
  [^File file]
  (.toInstant (Files/getLastModifiedTime (.toPath file) (make-array LinkOption 0))))

(defn ^File make-dir
  "Creates the directory in question and all of its parents.

  The arguments are the same taken by clojure.java.io/file. Returns
  the created directory."
  [f & others]
  {:pos [(.isDirectory %)]}
  (let [dir (apply io/file f others)]
    (.mkdirs dir)
    dir))

(defn make-empty-dir
  "Creates the directory in question and all of its parents.

  If the directory already exists, delete all existing files and
  sub-directories.

  The arguments are the same taken by clojure.java.io/file. Returns
  the created directory."
  [f & others]
  (let [dir (apply io/file f others)]
    (when (file-exists? dir)
      (run! #(io/delete-file %) (reverse (file-seq dir))))
    (make-dir dir)))

(defn posix-file-permissions
  "Returns a set containing posix file permissions for the supplied
  file."
  [^File file]
  (->> (Files/getPosixFilePermissions (.toPath file)        (make-array LinkOption 0))
       (map str)
       set))

(defn ^File relativize
  "Given a file and a base directory, returns a new file representing
  the relative path of the provided file in relation to the base
  directory."
  [^File file ^File base]
  (.. base toPath (relativize (.toPath file)) toFile))

(defn read-edn
  "Reads an EDN object and parses it as Clojure data.

  input can be any object supported by clojure.core/slurp."
  [input]
  (edn/read-string (slurp input)))

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
        input          (.getBytes (pr-str data) "UTF-8")]
    (.update message-digest input)
    (hex (.digest message-digest))))

(defn set-timestamp
  [^File file ^Long last-modified-time]
  (let [^FileTime file-time (FileTime/fromMillis last-modified-time)]
    (Files/setAttribute (.toPath file) "lastModifiedTime" file-time (make-array LinkOption 0))))
