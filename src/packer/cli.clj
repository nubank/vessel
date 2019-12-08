(ns packer.cli
  "Functions for dealing with command line input and output."
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.tools.cli :as tools.cli]
            [packer.misc :as misc :refer [with-stderr]]))

(def ^:private help
  "Help option."
  ["-?" "--help"
   :id :help
   :desc "Show this help message and exit"])

(defn- show-program-help?
  "Shall Packer show the help message?"
  [{:keys [arguments options]}]
  (or (and (empty? options)
           (empty? arguments))
      (options :help)))

(defn- show-help
  "Prints the help message.

  Return 0 indicating success."
  [{:keys [commands desc summary usage]}]
  (printf "Usage: %s%n%n" usage)
  (println desc)
  (println)
  (println "Options:")
  (println summary)
  (when commands
    (println)
    (println "Commands:")
    (run! println commands)
    (println)
    (println "See \"packer COMMAND --help\" for more information on a command"))
  0)

(defn- show-errors
  "Prints the error messages in the stderr.

  Returns 1 indicating an error in the execution."
  [{:keys [errors tip]}]
  (with-stderr
    (print "Packer: ")
    (run! println errors)
    (when tip
      (printf "See \"%s\"%n" tip))
    1))

(defn- command-not-found
  "Shows an error message saying that the command in question could not be found.

  Returns 127 (the Linux error code for non-existing commands)."
  [{:keys [cmd] :as result}]
  (show-errors (assoc result :errors [(format "\"%s\" isn't a Packer command" cmd)]))
  127)

(defn- run-command*
  "Calls the function assigned to the command in question.

  If the options map contains the `:help` key, shows the command's
  help message instead.

  Returns 0 indicating success."
  [{:keys [fn options] :as spec}]
  (if-not (options :help)
    (do (fn options)
        0)
    (show-help spec)))

(defn- parse-args
  [{:keys [cmd desc fn opts]} args]
  (-> (tools.cli/parse-opts args (conj opts help))
      (assoc :desc desc
             :fn fn
             :usage (format "packer %s [OPTIONS]" cmd)
             :tip (format "packer %s --help" cmd))))

(defn- run-command
  [command args]
  (let [{:keys [errors] :as result} (parse-args command args)]
    (if-not errors
      (run-command* result)
      (show-errors result))))

(defn- formatted-commands
  "Given a program spec, returns a sequence of formatted commands to be
  shown in the help message."
  [program]
  (let [lines (->> program
                   :commands
                   (map #(vector (first %) (:desc (second %))))
                   (sort-by first))
        lengths (map count (apply map (partial max-key count)  lines))]
    (tools.cli/format-lines lengths lines)))

(defn- parse-input
  [{:keys [desc] :as program} args]
  (let [{:keys [arguments] :as result}
        (tools.cli/parse-opts args
                              [help]
                              :in-order true)
        [cmd & args] arguments]
    (assoc result
           :args args
           :cmd cmd
           :commands (formatted-commands program)
           :desc desc
           :tip "packer --help"
           :usage (format "packer [OPTIONS] COMMAND"))))

(defn- run*
  [program input]
  (let [{:keys [errors cmd args] :as result} (parse-input program input)
        spec (get-in program [:commands cmd])]
    (cond
      errors (show-errors result)
      (show-program-help? result) (show-help result)
      (nil? spec) (command-not-found result)
      :else (run-command (assoc spec :cmd cmd) args))))

(defn run
  [program args]
  (try
    (run* program args)
    (catch Exception e
      (show-errors {:errors [(.getMessage e)]}))))

(defn- split-at-colon
  [^String input ^String message]
  (let [parts (string/split input #"\s*:\s*")]
    (if (= 2 (count parts))
      parts
      (throw (IllegalArgumentException. message)))))

(defn parse-attribute
  "Takes an attribute specification in the form key:value and returns a
  tuple where the first element is the key (as a keyword) and the
  second one is the value."
  [^String input]
  (let [key+value (split-at-colon input "Invalid attribute format. Please,
  specify attributes in the form key:value")]
    (update key+value 0 keyword)))

(defn parse-extra-file
  "Takes an extra file specification in the form `source:target` and
  returns a map containing two keys: :source and :target (both
  instances of java.io.File)."
  [^String input]
  (let [source+target (split-at-colon input "Invalid extra-file format. Please, specify extra
    files in the form source:target")]
    (zipmap [:source :target] (map io/file source+target))))

(def file-or-dir-must-exist
  [misc/file-exists? "no such file or directory"])

(def source-must-exist
  [#(misc/file-exists? (:source %)) "no such file or directory"])

(defn repeat-option
  [m k v]
  (update-in m [k] (fnil conj #{}) v))
