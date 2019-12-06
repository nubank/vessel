(ns packer.cli-test
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.test :refer :all]
            [packer.cli :as cli]))

(defmacro with-err-str
  [& body]
  `(let [writer# (java.io.StringWriter.)]
     (binding [*err* writer#]
       ~@body
       (str writer#))))

(deftest show-errors-test
  (testing "prints the error message along with an optional tip in the stderr"

    (is (= "Packer: Error\n"
           (with-err-str
             (cli/show-errors {:errors ["Error"]}))))

    (is (= "Packer: Error\nSee \"anything\"\n"
           (with-err-str
             (cli/show-errors {:errors ["Error"]
                               :tip "anything"})))))

  (testing "returns 1 indicating a failure"
    (is (= 1
           (cli/show-errors {:errors ["Error"]})))))

(defn greet
  [{:keys [names]}]
  (println "Hello"
           (string/join ", " names)))

(defn goodbye
  [{:keys [names]}]
  (println "Goodbye"
           (string/join ", " names)))

(def packer
  {:desc "FIXME"
   :commands
   {"greet"
    {:desc "Say a hello message for someone"
     :fn greet
     :opts [["-n" "--name NAME"
             :id :names
             :desc "Name of the person to greet"
             :assoc-fn cli/repeat-option]]}
    "goodbye"
    {:desc "Print a goodbye message"
     :fn greet
     :opts [["-n" "--name NAME"
             :id :names
             :desc "Name of the person to say goodbye to"
             :assoc-fn cli/repeat-option]]}}})

(deftest run-packer-test
  (testing "calls the function assigned to the command in question"
    (is (= "Hello John Doe\n"
           (with-out-str (cli/run-packer packer ["greet"
                                                 "-n" "John Doe"])))))

  (testing "the function `repeat-option`, when assigned to the `:assoc-fn`
  option, allows the flag to be repeated multiple times"
    (is (= "Hello John Doe, Jane Doe\n"
           (with-out-str (cli/run-packer packer ["greet"
                                                 "-n" "John Doe"
                                                 "-n" "Jane Doe"])))))

  (testing "returns 0 indicating success"
    (is (= 0
           (cli/run-packer packer ["greet"
                                   "-n" "John Doe"]))))

  (testing "shows the help message when one calls Packer with no arguments or with
the help flag"
    (are [args] (= "Usage: packer [OPTIONS] COMMAND\n\nFIXME\n\nOptions:\n  -?, --help  Show this help message and exit\n\nCommands:\n  greet    Say a hello message for someone\n  goodbye  Print a goodbye message\n\nSee \"packer COMMAND --help\" for more information on a command\n"
                   (with-out-str
                     (cli/run-packer packer args)))
      []
      ["-?"]
      ["--help"]))

  (testing "shows the help message for the command in question"
    (is (= "Usage: packer greet [OPTIONS]

Say a hello message for someone

Options:
  -n, --name NAME  Name of the person to greet
  -?, --help       Show this help message and exit\n"
           (with-out-str
             (cli/run-packer packer ["greet" "--help"])))))

  (testing "returns 0 after showing the help message"
    (is (= 0
           (cli/run-packer packer ["--help"])))
    (is (= 0
           (cli/run-packer packer ["goodbye" "--help"]))))

  (testing "shows a meaningful message when Packer is called with wrong options"
    (is (= "Packer: Unknown option: \"--foo\"\nSee \"packer --help\"\n"
           (with-err-str
             (cli/run-packer packer ["--foo"])))))

  (testing "returns 1 indicating the error"
    (is (= 1
           (cli/run-packer packer ["--foo"]))))

  (testing "shows a meaningful message when the command in question doesn't
  exist"
    (is (= "Packer: \"build\" isn't a Packer command\nSee \"packer --help\"\n"
           (with-err-str
             (cli/run-packer packer ["build" "--help"])))))

  (testing "returns 127 indicating that the command could not be found"
    (is (= 127
           (cli/run-packer packer ["build" "--help"]))))

  (testing "shows a meaningful message when a command is mistakenly called"
    (is (= "Packer: Missing required argument for \"-n NAME\"\nSee \"packer greet --help\"\n"
           (with-err-str
             (cli/run-packer packer ["greet" "-n"])))))

  (testing "returns 1 indicating an error"
    (is (= 1
           (cli/run-packer packer ["greet" "-n"])))))

(deftest parse-attribute-test
  (testing "parses the input in the form `key:value`"
    (is (= [:name "my-app"]
           (cli/parse-attribute "name:my-app"))))

  (testing "throws an exception when the input is malformed"
    (is (thrown-with-msg? IllegalArgumentException #"^Invalid attribute format.*"
                          (cli/parse-attribute "name")))))

(deftest parse-extra-file-test
  (testing "parses the input in the form `source:target`"
    (is (= {:source (io/file "web.xml")
            :target (io/file "/app")}
           (cli/parse-extra-file "web.xml:/app"))))

  (testing "throws an exception when the input is malformed"
    (is (thrown-with-msg? IllegalArgumentException #"^Invalid extra-file format.*"
                          (cli/parse-extra-file "web.xml")))))

(defn validate
  [[validate-fn message] input]
  (when-not (validate-fn input)
    message))

(deftest file-or-dir-must-exist-test
  (is (= "no such file or directory"
         (validate cli/file-or-dir-must-exist (io/file "foo.txt"))))

  (is (nil?
       (validate cli/file-or-dir-must-exist (io/file "deps.edn")))))

(deftest source-must-exist-test
  (is (= "no such file or directory"
         (validate cli/source-must-exist {:source (io/file "foo.txt")})))

  (is (nil?
       (validate cli/source-must-exist {:source (io/file "deps.edn")}))))
