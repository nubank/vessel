(ns vessel.cli
  "Functions for dealing with command line input and output."
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.tools.cli :as tools.cli]
            [vessel.misc :as misc :refer [with-stderr]]))

(def ^:private help
  "Help option."
  ["-?" "--help"
   :id :help
   :desc "Show this help message and exit"])

(defn- show-program-help?
  "Shall Vessel show the help message?"
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
    (println "See \"vessel COMMAND --help\" for more information on a command"))
  0)

(defn- show-errors
  "Prints the error messages in the stderr.

  Returns 1 indicating an error in the execution."
  [{:keys [errors tip]}]
  (with-stderr
    (print "Vessel: ")
    (run! println errors)
    (when tip
      (printf "See \"%s\"%n" tip))
    1))

(defn- command-not-found
  "Shows an error message saying that the command in question could not be found.

  Returns 127 (the Linux error code for non-existing commands)."
  [{:keys [cmd] :as result}]
  (show-errors (assoc result :errors [(format "\"%s\" isn't a Vessel command" cmd)]))
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
             :usage (format "vessel %s [OPTIONS]" cmd)
             :tip (format "vessel %s --help" cmd))))

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
  (let [lines   (->> program
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
           :tip "vessel --help"
           :usage (format "vessel [OPTIONS] COMMAND"))))

(defn- run*
  [program input]
  (let [{:keys [errors cmd args] :as result} (parse-input program input)
        spec                                 (get-in program [:commands cmd])]
    (cond
      errors                      (show-errors result)
      (show-program-help? result) (show-help result)
      (nil? spec)                 (command-not-found result)
      :else                       (run-command (assoc spec :cmd cmd) args))))

(defn run
  [program args]
  (try
    (run* program args)
    (catch Throwable t
      (show-errors {:errors [(.getMessage t)]})
      (when-let [cause (:vessel.error/throwable (ex-data t))]
        (.printStackTrace cause))
      1)))

(defn- split-at-colon
  "Splits the input into two parts divided by the first colon.

  Throws an IllegalArgumentException with the supplied message if the
  input is malformed (i.e. can't be split as explained above)."
  [^String input ^String message]
  (let [parts (vec (rest (re-matches #"([^:]+):(.*)" input)))]
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

(defn- parse-churn
  [^String input]
  (if-not input
    0
    (try
      (Integer/parseInt input)
      (catch NumberFormatException _
        (throw (IllegalArgumentException. (format "Expected an integer but got '%s' in the churn field of the extra-path specification." input)))))))

(defn parse-extra-path
  "Takes an extra path specification in the form `source:target` or
  `source:target@churn` and returns a map containing the following
  keys:

  :source java.io.File

  The file to be copied to the resulting image.

  :target java.io.File

  The absolute path to which the file in question must be copied.

  :churn Integer

  An integer indicating how often this file changes. Defaults to 0."
  [^String input]
  (let [[source rest]  (split-at-colon input
                                       "Invalid extra-path format.
Please, specify extra paths in the form source:target or source:target@churn.")
        [target churn] (string/split rest #"@")]
    {:source (io/file source)
     :target (io/file target)
     :churn  (parse-churn churn)}))

(def file-or-dir-must-exist
  [misc/file-exists? "no such file or directory"])

(def source-must-exist
  [#(misc/file-exists? (:source %)) "no such file or directory"])

(defn repeat-option
  [m k v]
  (update-in m [k] (fnil conj #{}) v))
