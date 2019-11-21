(ns packer.git
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as string]))

(defn- exec
  "Runs a Git command and returns the result as a seq of lines.

  Throws an exception with a meaningful message when the command in question fails"
  [args & {:keys [working-dir]
           :or {working-dir (io/file ".")}}]
  (let [split-at-line-breaks #(string/split % (re-pattern (System/lineSeparator)))
        {:keys [exit out err]}
        (apply shell/sh (flatten
                         ["git"
                          args
                          :env (into {} (System/getenv))
                          :dir working-dir]))]
    (if (zero? exit)
      (split-at-line-breaks out)
      (throw (ex-info err
                      {:type ::error
                       :command (apply str "git " args)
                       :working-dir working-dir})))))

(defn rev-parse-head
  "Same as git rev-parse HEAD.

  Returns the Git sha of the HEAD commit in the current branch."
  ^String
  [& args]
  (first (apply exec ["rev-parse" "HEAD"] args)))

(defn current-branch
  "Returns the name of the current branch."
  ^String
  [& args]
  (->> (apply exec ["branch"] args)
       (keep (fn [branch]
               (when (string/starts-with? branch "*")
                 (string/trim (subs branch 1)))))
       first))
