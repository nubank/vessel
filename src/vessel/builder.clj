(ns vessel.builder
  "Builder for Clojure applications."
  (:refer-clojure :exclude [compile])
  (:require [clojure.java.classpath :as java.classpath]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as string]
            [clojure.tools.namespace.find :as namespace.find]
            [spinner.core :as spinner]
            [vessel.misc :as misc]
            [vessel.resource-merge :as merge]
            [vessel.sh :as sh])
  (:import [clojure.lang Sequential Symbol]
           [java.io File InputStream]
           [java.util.jar JarEntry JarFile]))

(defn- ^Symbol parent-ns
  "Given a ns symbol, returns its parent, that's to say, the same ns without its last segment.

  For instance:

  (parent-ns 'clj-jwt.base64.ByteArrayInput) => 'clj-jwt.base64"
  [^Symbol ns]
  (->> (string/split (str ns) #"\.")
       drop-last
       (string/join ".")
       symbol))

(defn-   ^Symbol class-file->ns
  "Turns a .class file into the corresponding namespace symbol."
  [^File class-file]
  (let [ns (first (string/split (.getPath class-file) #"\$|__init|\.class"))]
    (-> ns
        (string/replace "/" ".")
        (string/replace "_" "-")
        symbol)))

(defn ^File get-class-file-source
  "Given a map from ns symbols to their sources (either directories or
  jar files on the classpath) and a file representing a compiled
  class, returns the source where the class in question comes from."
  [namespaces ^File class-file]
  (let [ns (class-file->ns class-file)]
    (get namespaces ns
         (get namespaces (parent-ns ns)))))

(defn compile-java-sources
  "Compiles Java sources present in the source paths and writes
  resulting .class files to the supplied target directory."
  [classpath source-paths ^File target-dir]
  (let [java-sources (->> source-paths
                          (map file-seq)
                          flatten
                          (filter #(string/ends-with? (.getName %) ".java")))]
    (when (seq java-sources)
      (sh/javac classpath target-dir java-sources))))

(defn- do-compile
  "Compiles the main-ns by writing compiled .class files to target-dir.

  Displays a spin animation during the process."
  [^Symbol main-ns ^Sequential classpath source-paths ^File target-dir]
  (let [forms `(try
                 (binding [*compile-path*     ~(str target-dir)]
                   (clojure.core/compile (symbol ~(name main-ns))))
                 (catch Throwable err#
                   (println)
                   (.printStackTrace err#)
                   (System/exit 1)))
        _     (misc/log :info "Compiling %s..." main-ns)
        spin  (spinner/create-and-start!)]
    (try
      (compile-java-sources classpath source-paths target-dir)
      (sh/clojure classpath
                  "--eval"
                  (pr-str forms))
      (finally
        (spinner/stop! spin)
        (println)))))

(defn- find-namespaces
  "Returns all namespaces declared within the file (either a directory
  or a jar file)."
  [^File file]
  (cond
    (.isDirectory file)             (namespace.find/find-namespaces-in-dir file)
    (java.classpath/jar-file? file) (namespace.find/find-namespaces-in-jarfile (JarFile. file))))

(defn- find-namespaces-on-classpath
  "Given a sequence of files on the classpath, returns a map from
  namespace symbols to their sources (either directories or jar
  files)."
  [classpath-files]
  (->> classpath-files
       (mapcat #(map vector (find-namespaces %) (repeat %)))
       (remove (comp nil? first))
       (into {})))

(defn compile
  "Compiles the ns symbol (that must have a gen-class directive) into a
  set of .class files.

  Returns a map of compiled class files (as instances of
  java.io.File) to their sources (instances of java.io.File as well
  representing directories or jar files on the classpath)."
  [classpath-files ^Symbol main-class source-paths ^File target-dir]
  (let [namespaces  (find-namespaces-on-classpath classpath-files)
        classes-dir (misc/make-dir target-dir "classes")
        classpath (cons classes-dir classpath-files)
        _           (do-compile main-class  classpath source-paths classes-dir)]
    (reduce (fn [result ^File class-file]
              (let [source-file (or (get-class-file-source namespaces (misc/relativize class-file classes-dir))
                                    ;; Defaults to the first element of source-paths if the class file doesn't match any known source.
                                    (first source-paths))]
                (assoc result class-file source-file)))
            {}
            (misc/filter-files (file-seq classes-dir)))))

(defn copy-libs
  "Copies third-party libraries to the lib directory under target-dir.

  Returns a sequence of the copied libraries as java.io.File objects."
  [libs ^File target-dir]
  (let [lib-dir (misc/make-dir target-dir "lib")]
    (mapv (fn                                                                                                                          [^File lib]
            (let [^File dest-file (io/file lib-dir (.getName lib))]
              (io/copy lib dest-file)
              (misc/set-timestamp dest-file (.lastModified dest-file))
              dest-file))
          libs)))

(defn- merge-data-readers
  [^InputStream src ^File target]
  (let [new-data-readers (misc/read-edn src)
        old-data-readers (when (misc/file-exists? target) (misc/read-edn target))]
    (->> (merge old-data-readers new-data-readers)
         pr-str
         (spit target))))

(defn- ^File copy-or-merge
  "Copies the src InputStreaam to the target file, or merges a file that matches a merge file.

  Creates the directory structure for the file.

  Returns a tuple of [merged? merged-paths]; if not merged, the file is copied to target-file.

  If merged? is true, then the merged-paths is updated but no file is written yet."
  [^File classpath-root ^InputStream src ^File target-file last-modified-time merge-rules merged-paths]
  (io/make-parents target-file)
  (let [[merged? _merged-paths' :as result] (merge/execute-merge-rules classpath-root
                                                                       src
                                                                       target-file
                                                                       last-modified-time
                                                                       merge-rules
                                                                       merged-paths)]
    (when-not merged?
      (io/copy src target-file)
      (misc/set-timestamp target-file last-modified-time))
    result))

(defn- copy-or-merge-files
  [classpath-root merge-rules merged-paths files]
  (let [
        f (fn [{:keys [result merged-paths]} {:keys [^File target-file ^InputStream input-stream last-modified]}]
            (let [[merged? merged-paths'] (copy-or-merge classpath-root
                                                         input-stream
                                                         target-file
                                                         last-modified
                                                         merge-rules
                                                         merged-paths)]
              {:result       (cond-> result (not merged?) (conj target-file))
               :merged-paths merged-paths'}))
        {:keys [result merged-paths]} (reduce f
                                              {:result       []
                                               :merged-paths merged-paths}
                                              files)]
    [result merged-paths]))

(defn- copy-files-from-jar
  "Copies files from within the jar file to the target directory."
  [^File jar-root ^File target-dir merge-rules merged-paths]
  (with-open [jar-file (JarFile. jar-root)]
    (->> jar-file
         .entries
         enumeration-seq

         (remove #(.isDirectory ^JarEntry %))
         (map (fn [^JarEntry entry]
                {:target-file   (io/file target-dir (.getName entry))
                 :input-stream  (.getInputStream jar-file entry)
                 :last-modified (.getTime entry)}))
         (copy-or-merge-files jar-root merge-rules merged-paths))))

(defn- copy-files-from-dir
  "Copies files (typically resources) from source to target."
  [^File dir-root ^File target-dir merge-rules merged-paths]
  (->> dir-root
       file-seq
       misc/filter-files
       (map (fn [^File file]
              {:target-file   (io/file target-dir (misc/relativize file dir-root))
               :input-stream  (io/input-stream file)
               :last-modified (.lastModified file)}))
       (copy-or-merge-files dir-root merge-rules merged-paths)))

(defn- copy-files*
  "Copies files from a classpath root to the target directory.

  Returns a tuple of [copied-files merged-paths]; copied files are files in the target
  directory, merged-paths is a map of data for any files that are require merge logic."
  [^File classpath-root ^File target-dir merge-rules merged-paths]
  (let [f (if (.isDirectory classpath-root) copy-files-from-dir copy-files-from-jar)]
    (f classpath-root target-dir merge-rules merged-paths)))

(defn copy-files
  "Iterates over the classpath roots (source directories or library jars) and copies
  all files within to the target directory (on the file system).

  Certain kinds of files (such as `data_readers.clj`) may occur multiple times, and
  must be merged.  The merge-rules argument is a list of rules to apply,
  see the [[base-rules]].

  Output files have the same last modified time as source files.

  Returns a map from target files (a File) to their source classpath root."
  [classpath-roots ^File target-dir merge-rules]
  (let [classes-dir (misc/make-dir target-dir "classes")
        f           (fn [{:keys [result merged-paths]} source-dir]
                      (let [[output-files merged-paths'] (copy-files* source-dir classes-dir merge-rules merged-paths)
                            result' (->> output-files
                                         (remove nil?)
                                         (reduce #(assoc %1 %2 source-dir) result))]
                        {:result       result'
                         :merged-paths merged-paths'}))
        {:keys [result merged-paths]} (reduce f nil classpath-roots)]
    (merge result (merge/write-merged-paths merged-paths))))

(defn build-app
  "Builds the Clojure application.

  Performs an ahead-of-time (AOT) compilation of the ns
  symbol (:main-class) into a set of class files. The compiled classes
  will be written at :target-dir/WEB-INF/classes along with any
  resource files (including .clj or .cljc ones) found in the
  respective jars declared on the classpath (:classpath-files). Other
  libraries present on the classpath will by copied to
  :target-dir/WEB-INF/lib.

  The options map expects the following keys:

  * :classpath-files (seq of java.io.File objects) representing the
  classpath of the Clojure application;
  * :main-class (symbol) application entrypoint containing
  a :gen-class directive and a -main function;
  * :resource-paths (set of java.io.File objects) a set of paths
  containing resource files of the Clojure application;
  * :target-dir (java.io.File) an existing directory where the
  application's files will be written to.

  Returns a map containing the following namespaced keys:
  * :app/classes - a map from target files (compiled class files and
  resources) to their source classpath root (either directories or jar files present
  on the classpath);
  * :app/lib - a sequence of java.io.File objects containing libraries
  that the application depends on."
  [{:keys [classpath-files ^Symbol main-class resource-paths source-paths ^File target-dir]}]
  {:pre [classpath-files main-class source-paths target-dir]}
  (let [web-inf        (misc/make-dir target-dir "WEB-INF")
        classes        (compile classpath-files main-class source-paths web-inf)
        dirs+jar-files (set/union resource-paths (set (vals classes)))
        libs           (misc/filter-files (set/difference (set classpath-files) dirs+jar-files))
        resource-files (copy-files dirs+jar-files web-inf merge/base-rules)]
    #:app{:classes (merge classes resource-files)
          :lib     (copy-libs libs web-inf)}))
