(ns vessel.clojure.builder
  "Builder for Clojure applications."
  (:refer-clojure :exclude [compile])
  (:require [clojure.java.classpath :as java.classpath]
            [vessel.sh :as sh]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.tools.namespace.file :as namespace.file]
            [clojure.tools.namespace.find :as namespace.find]
            [spinner.core :as spinner]
            [vessel.misc :as misc]
            [vessel.v1 :as v1])
  (:import [clojure.lang IPersistentMap IPersistentSet ISeq Sequential Symbol]
           [java.io BufferedInputStream BufferedOutputStream ByteArrayInputStream File FileInputStream FileOutputStream InputStream]
           [java.net URL URLClassLoader]
           java.nio.file.attribute.FileTime
           [java.util.jar JarEntry JarFile JarOutputStream Manifest]))

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

(defn- ^File get-class-file-source
  "Given a map of ns symbols to their sources (either directories or jar files
  on the classpath) and a file representing a compiled class, returns the source
  where the class in question comes from.

  This function must always return a known source for the .class file in
  question. If it can't determine the source, it throws an
  IllegalStateException."
  [namespaces ^File class-file]
  (let [ns (class-file->ns class-file)]
    (or (get namespaces ns)
        (get namespaces (parent-ns ns))
        (throw (IllegalStateException. (str "Unknown source for .class file " class-file))))))

(defn- do-compile
  "Compiles the main-ns by writing compiled .class files to target-dir.

  Displays a spin animation during the process."
  [^Symbol main-ns ^Sequential classpath ^File target-dir ^IPersistentMap compiler-opts]
  (let [forms `(try
                 (binding [*compile-path* ~(str target-dir)
                           *compiler-options* ~compiler-opts]
                   (clojure.core/compile (symbol ~(name main-ns))))
                 (catch Throwable err#
                   (println)
                   (.printStackTrace err#)
                   (System/exit 1)))
        _            (misc/log :info "Compiling %s..." main-ns)
        spin         (spinner/create-and-start!)]
    (try
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
  "Given a sequence of files on the classpath, returns a map of namespace
  symbols to their sources (either directories or jar files)."
  [classpath]
  (->> classpath
       (mapcat #(map vector (find-namespaces %) (repeat %)))
       (remove (comp nil? first))
       (into {})))

(defn compile
  "Compiles the ns symbol (that must have a gen-class directive) into a set of
  .class files.

  Returns a map of compiled class files (as instances of java.io.File) to their
  sources (instances of java.io.File too) representing directories or jar files
  on the classpath."
  [^Symbol main-ns ^Sequential classpath ^File target-dir ^IPersistentMap compiler-opts]
  (let [namespaces (find-namespaces-on-classpath classpath)
        _          (do-compile main-ns classpath target-dir compiler-opts)]
    (reduce (fn [result ^File class-file]
              (assoc result class-file
                     (get-class-file-source namespaces (misc/relativize class-file target-dir))))
            {}
            (misc/filter-files (file-seq target-dir)))))

(defn- merge-data-readers
  [^InputStream source ^File target]
  (let [new-data-readers (misc/read-edn source)
        old-data-readers (when (misc/file-exists? target) (misc/read-edn target))]
    (->> (merge old-data-readers new-data-readers)
         pr-str
         (spit target))))

(defn- data-readers-file?
  "Returns true if the java.io.File object represents a Clojure data-readers
  file (data_readers.clj or data_readers.cljc)."
  [^File file]
  (re-find #"^data_readers\.cljc?$"
           (.getName file)))

(defn- manifest-mf-file?
  "Returns true if the java.io.File object represents a MANIFEST.MF file."
  [^File file]
  (string/ends-with? (string/lower-case (.getPath file))
                     "meta-inf/manifest.mf"))

(defn- includes?
  "Whether or not the java.io.File object in question must be included in the
  artifact being built."
  [^File file ^IPersistentSet exclusions]
  (and (not (manifest-mf-file? file))
       (every? #(not (re-find % (.getPath file))) exclusions)))

(defn- ^File copy
  "Copies source to target and returns it (the target file).

  Gives a special treatment to data-readers, by merging multiple ones
  into their respective files (data_readers.clj or
  data_readers.cljc)."
  [^InputStream source ^File target ^File base-dir ^IPersistentSet exclusions]
  (when (includes? target exclusions)
    (io/make-parents target)
    (if (data-readers-file? target)
      (merge-data-readers source target)
      (io/copy source target))
    target))

(defn- copy-files-from-jar
  "Copies files from within the jar file to the target directory."
  [^File jar ^File target-dir ^IPersistentSet exclusions]
  (with-open [jar-file (JarFile. jar)]
    (->> jar-file
         .entries
         enumeration-seq
         (mapv (fn [^JarEntry jar-entry]
                 (let [^File target-file (io/file target-dir (.getName jar-entry))]
                   (when-not (.isDirectory jar-entry)
                     (copy (.getInputStream jar-file jar-entry) target-file target-dir exclusions))))))))

(defn- copy-files-from-dir
  "Copies files (typically resources) from source to target."
  [^File source ^File target-dir ^IPersistentSet exclusions]
  (->> source
       file-seq
       misc/filter-files
       (mapv (fn [^File file]
               (let [target-file (io/file target-dir (misc/relativize file source))]
                 (copy (io/input-stream file) target-file target-dir exclusions))))))

(defn- copy-files*
  [^File source ^File target-dir ^IPersistentSet exclusions]
  (if (.isDirectory source)
    (copy-files-from-dir source target-dir exclusions)
    (copy-files-from-jar source target-dir exclusions)))

(defn- ^IPersistentMap copy-files
  "Iterates over the files (typically directories and jar files) by
  copying their content to the target directory. Data
  readers (declared in data_readers.clj or data_readers.cljc in the
  root of the classpath) are merged together into their respective
  files.

  Returns a map of target files to their sources."
  [^ISeq classpath ^File target-dir ^IPersistentSet exclusions]
  (->> classpath
       (mapcat #(map vector (remove nil? (copy-files* % target-dir exclusions)) (repeat %)))
       (into {})))

(defn ^IPersistentMap build-application
  "Builds a Clojure application.

  Compiles ahead-of-time (AOT) all Clojure sources present in the application's
  classpath that the supplied main ns depends on. Writes resulting .class files
  to the supplied target directory along with any resource files found in the
  classpath. Also copies other files from within the libraries (jar files)
  referenced by the application in question to the same location.

  Manifest is a persistent map as spec'ed by :vessel.v1/manifest. The following
  keys are specially meaningful:

  :compiler-opts IPersistentMap

  A map containing the flags accepted by Clojure compiler. For further details,
  refer to: https://clojure.org/reference/compilation.

  :exclusions set of java.util.regex.Pattern

  A set of regexes to match files against. Matching files will be
  excluded from the final artifact built.

  :main-ns Symbol

  A symbol representing the aplication's main namespace. It must contains a
  :gen-class directive.

  :resource-paths ISeq of java.io.File

  The application's resource paths.

    :source-paths ISeq of java.io.File

  The application's source paths.

    Deps is a sequence of java.io.File objects representing all dependencies of
  the application. See also vessel.clojure.classpath/assemble-deps.

  Target-dir is a java.io.File object representing the directory to write
  compiled classes and libraries to.

  Returns a persistent map of java.io.File to java.io.File objects mapping
  target files to their sources. This data structure aids Vessel to determine
  which files belong to each application layer during the containerization
  process.

  See also: vessel.clojure.classpath and vessel.reader."
  [^IPersistentMap manifest ^Sequential deps ^File target-dir]
  (let [{::v1/keys [compiler-opts, exclusions, main-ns, resource-paths, source-paths]} manifest
        ^ISeq classpath                                                                (concat source-paths resource-paths deps)
        ^IPersistentMap classes                                                        (compile main-ns classpath target-dir compiler-opts)
        ^IPersistentMap remaining-files                                                (copy-files classpath target-dir exclusions)]
    (merge classes remaining-files)))

(defn- write-bytes
  "Writes the content of the supplied file to the jar file being created."
  [^JarOutputStream jar-stream ^File file]
  (let [input  (BufferedInputStream. (FileInputStream. file))
        buffer (byte-array 1024)]
    (loop []
      (let [count (.read input buffer)]
        (when-not (< count 0)
          (.write jar-stream buffer 0 count)
          (recur))))))

(defn- add-jar-entry
  "Writes the supplied file to the jar being created. The base-dir is used to
  properly resolve the path of the jar entry within the jar file."
  [^JarOutputStream jar-stream ^File file-to-add ^File base-dir]
  (let [^String path-in-jar (.getPath (misc/relativize file-to-add base-dir))
        ^JarEntry jar-entry (JarEntry. path-in-jar)]
    (.setLastModifiedTime jar-entry
                          (FileTime/from (misc/last-modified-time file-to-add)))
    (.putNextEntry jar-stream jar-entry)
    (write-bytes jar-stream file-to-add)
    (.closeEntry jar-stream)))

(defn- ^Manifest generate-jar-manifest
  ""
  [^Symbol main-ns]
  (letfn [(ns->class-name [^Symbol ns]
            (misc/kebab-case (string/replace (name ns) #"\." "/")))
          (render [attributes]
            (->> attributes
                 (remove nil?)
                 (string/join (System/lineSeparator))
                 .getBytes
                 ByteArrayInputStream.
                 Manifest.))]
    (render
     ["Manifest-Version: 1.0"
      "Created-By: Vessel"
      (when main-ns
        (str "Main-Class: " (ns->class-name main-ns)))
      (System/lineSeparator)])))

(defn ^File bundle-up
  ""
  [^File jar ^Sequential files-to-be-bundled ^IPersistentMap settings]
  (let [{:keys [base-dir, main-ns]} settings]
    (with-open [jar-stream (JarOutputStream. (BufferedOutputStream. (FileOutputStream. jar)) (generate-jar-manifest main-ns))]
      (loop [files files-to-be-bundled]
        (let [^File next-entry (first files)]
          (if-not next-entry
            (do (.finish jar-stream)
                jar)
            (do
              (add-jar-entry jar-stream next-entry base-dir)
              (recur (next files)))))))))
