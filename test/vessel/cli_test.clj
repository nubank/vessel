(ns vessel.cli-test
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.test :refer :all]
            [vessel.cli :as cli]))

(defmacro with-err-str
  [& body]
  `(let [writer# (java.io.StringWriter.)]
     (binding [*err* writer#]
       ~@body
       (str writer#))))

(defn greet
  [{:keys [names]}]
  (println "Hello"
           (string/join ", " names)))

(defn goodbye
  [{:keys [names]}]
  (println "Goodbye"
           (string/join ", " names)))

(def vessel
  {:desc "FIXME"
   :commands
   {"greet"
    {:desc "Say a hello message for someone"
     :fn   greet
     :opts [["-n" "--name NAME"
             :id :names
             :desc "Name of the person to greet"
             :assoc-fn cli/repeat-option]]}
    "goodbye"
    {:desc "Print a goodbye message"
     :fn   greet
     :opts [["-n" "--name NAME"
             :id :names
             :desc "Name of the person to say goodbye to"
             :assoc-fn cli/repeat-option]]}
    "boom"
    {:desc "Simply blows up"
     :fn   (fn [_] (throw (Exception. "Boom!")))}}})

(deftest run-test
  (testing "calls the function assigned to the command in question"
    (is (= "Hello John Doe\n"
           (with-out-str (cli/run vessel ["greet"
                                          "-n" "John Doe"])))))

  (testing "the function `repeat-option`, when assigned to the `:assoc-fn`
  option, allows the flag to be repeated multiple times"
    (is (= "Hello John Doe, Jane Doe\n"
           (with-out-str (cli/run vessel ["greet"
                                          "-n" "John Doe"
                                          "-n" "Jane Doe"])))))

  (testing "returns 0 indicating success"
    (is (= 0
           (cli/run vessel ["greet"
                            "-n" "John Doe"]))))

  (testing "shows the help message when one calls Vessel with no arguments or with
the help flag"
    (are [args] (= "Usage: vessel [OPTIONS] COMMAND\n\nFIXME\n\nOptions:\n  -?, --help  Show this help message and exit\n\nCommands:\n  boom     Simply blows up\n  goodbye  Print a goodbye message\n  greet    Say a hello message for someone\n\nSee \"vessel COMMAND --help\" for more information on a command\n"
                   (with-out-str
                     (cli/run vessel args)))
      []
      ["-?"]
      ["--help"]))

  (testing "shows the help message for the command in question"
    (is (= "Usage: vessel greet [OPTIONS]

Say a hello message for someone

Options:
  -n, --name NAME  Name of the person to greet
  -?, --help       Show this help message and exit\n"
           (with-out-str
             (cli/run vessel ["greet" "--help"])))))

  (testing "returns 0 after showing the help message"
    (is (= 0
           (cli/run vessel ["--help"])))
    (is (= 0
           (cli/run vessel ["goodbye" "--help"]))))

  (testing "shows a meaningful message when Vessel is called with wrong options"
    (is (= "Vessel: Unknown option: \"--foo\"\nSee \"vessel --help\"\n"
           (with-err-str
             (cli/run vessel ["--foo"])))))

  (testing "returns 1 indicating the error"
    (is (= 1
           (cli/run vessel ["--foo"]))))

  (testing "shows a meaningful message when the command in question doesn't
  exist"
    (is (= "Vessel: \"build\" isn't a Vessel command\nSee \"vessel --help\"\n"
           (with-err-str
             (cli/run vessel ["build" "--help"])))))

  (testing "returns 127 indicating that the command could not be found"
    (is (= 127
           (cli/run vessel ["build" "--help"]))))

  (testing "shows a meaningful message when a command is mistakenly called"
    (is (= "Vessel: Missing required argument for \"-n NAME\"\nSee \"vessel greet --help\"\n"
           (with-err-str
             (cli/run vessel ["greet" "-n"])))))

  (testing "returns 1 indicating an error"
    (is (= 1
           (cli/run vessel ["greet" "-n"]))))

  (testing "shows an error message when the command throws an exception"
    (is (= "Vessel: Boom!\n"
           (with-err-str
             (cli/run vessel ["boom"])))))

  (testing "returns 1 indicating an error"
    (is (= 1
           (cli/run vessel ["boom"])))))

(deftest parse-attribute-test
  (testing "parses the input in the form `key:value`"
    (is (= [:name "my-app"]
           (cli/parse-attribute "name:my-app"))))

  (testing "throws an exception when the input is malformed"
    (is (thrown-with-msg? IllegalArgumentException #"^Invalid attribute format.*"
                          (cli/parse-attribute "name")))))

(deftest parse-extra-path-test
  (testing "parses the input in the form `source:target`"
    (is (= {:source (io/file "web.xml")
            :target (io/file "/app/web.xml")
            :churn  0}
           (cli/parse-extra-path "web.xml:/app/web.xml"))))

  (testing "parses the input in the form `source:target@churn`"
    (is (= {:source (io/file "web.xml")
            :target (io/file "/app/web.xml")
            :churn  2}
           (cli/parse-extra-path "web.xml:/app/web.xml@2"))))

  (testing "throws an exception when the input is malformed"
    (is (thrown-with-msg? IllegalArgumentException #"^Invalid extra-path format.*"
                          (cli/parse-extra-path "web.xml"))))

  (testing "throws an exception when the churn isn't an integer"
    (is (thrown-with-msg? IllegalArgumentException #"Expected an integer but got 'foo' in the churn field of the extra-path specification\."
                          (cli/parse-extra-path "web.xml:/app/web.xml@foo")))))

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
