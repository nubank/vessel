(ns packer.cli
  (:gen-class)
  (:require [clojure.java.io :as io]
            [packer.api :as api]
            [clojure.tools.cli :as tools.cli]
            [packer.misc :as misc]
            [clojure.string :as string]))

(def ^:private cwd (io/file "."))

(def ^:private file-or-dir-must-exist
  [misc/file-exists? "no such file or directory"])

(def ^:private source-must-exist
  [#(misc/file-exists? (:source %)) "no such file or directory"])

(defn- accumulate
  [m k v]
  (update-in m [k] (fnil conj #{}) v))

(defn- parse-extra-file
  [value]
  (let [source+target (string/split value #"\s*:\s*")]
    (if (= 2 (count source+target))
      (zipmap [:source :target] (map io/file source+target))
      (throw (IllegalArgumentException.
              "Invalid extra-file format. Please, specify extra
    files in the form source:target")))))

(defmacro with-stderr
  "Binds *err* to *out* and evaluates body."
  [& body]
  `(binding [*out* *err*]
     ~@body))

(defn- run-command*
  [{:keys [desc fn options summary usage]}]
  (if-not (options :help)
    (fn options)
    (do (printf "Usage: %s%n%n" usage)
        (println desc)
        (println)
        (println "Options:")
        (println summary))))

(def ^:private help
  "Help option."
  ["?" "--help"
   :id :help
   :desc "show this help and exit"])

(defn- parse-args
  [{:keys [cmd desc opts]} args]
  (-> (tools.cli/parse-opts args (conj opts help))
      (assoc :desc desc
             :usage (format "packer %s [OPTIONS]" cmd)
             :tip (format "See packer %s --help" cmd))))

(defn- run-command
  [command args]
  (let [{:keys [error see] :as result} (parse-args command args)]
    (if-not error
      (run-command* result)
      (with-stderr
        (println error)
        (println see)))))

(defn run-program
  [program [command & args]]
  (if-let [command-spec (get program command)]
    (run-command (assoc command-spec :cmd command) args)
    (with-stderr
      (printf "'%s' isn't a Packer command.%n" command)
      (println "See packer --help"))))

(def packer
  {"containerize"
   {:desc "Turns an input jar into a lightweight
  container according to provided options"
    :fn api/containerize
    :opts [["-a" "--app-root PATH"
            :id :app-root
            :desc "app root of the container image. Classes and
                      resource files will be copied to relative paths to the app
                      root."
            :default (io/file "/app")
            :parse-fn io/file]
           ["-e" "--extra-file PATH"
            :id :extra-files
            :desc "extra files to be copied to the container
                      image. The value must be passed in the form source:target
                      and this option can be repeated many times"
            :parse-fn parse-extra-file
            :validate source-must-exist
            :assoc-fn accumulate]
           ["-i" "--input JAR"
            :id :jar-file
            :desc "jar file to be containerized"
            :parse-fn io/file
            :validate file-or-dir-must-exist]
           ["-I" "--internal-deps REGEX"
            :id :internal-deps-re
            :desc "java regex to determine internal dependencies. Can be
            repeated many times for a logical or"
            :parse-fn re-pattern
            :assoc-fn accumulate]
           ["-m" "--manifest PATH"
            :id :manifest
            :desc "manifest file describing the image to be built"
            :parse-fn io/file
            :validate file-or-dir-must-exist
            :assoc-fn #(assoc %1 %2 (misc/read-json %3))]
           ["-p" "--project-root PATH"
            :id :project-root
            :desc "root dir of the project containing the source
                      code. Packer inspects the project to obtain some useful
                      insights about how to organize layers."
            :default cwd
            :parse-fn io/file
            :validate file-or-dir-must-exist]
           ["-o" "--output PATH"
            :id :tarball
            :desc "path to save the tarball containing the built image"
            :default (io/file "image.tar")
            :parse-fn io/file]]}})
