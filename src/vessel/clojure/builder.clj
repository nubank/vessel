(ns vessel.clojure.builder
  "Builder for Clojure applications."
  (:refer-clojure :exclude [compile])
  (:require [clojure.java.classpath :as java.classpath]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.tools.namespace.file :as namespace.file]
            [clojure.tools.namespace.find :as namespace.find]
            [spinner.core :as spinner]
            [vessel.misc :as misc]
            [vessel.v1 :as v1])
  (:import [clojure.lang IPersistentMap ISeq Sequential Symbol]
           [java.io BufferedInputStream BufferedOutputStream File FileInputStream FileOutputStream InputStream]
           [java.net URL URLClassLoader]
           java.nio.file.attribute.FileTime
           [java.util.jar JarEntry JarFile JarOutputStream]))

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

(defn- rethrow-compilation-error
  "Unwrap the actual compilation exception and rethrow it as an
  ExceptionInfo that will be properly handled by Vessel."
  [^Symbol main-ns throwable]
  (throw (ex-info (str "Failed to compile " main-ns)
                  #:vessel.error{:category  :vessel/compilation-error
                                 :throwable (ex-cause (ex-cause throwable))})))

(defn- main-args
  "Returns a Java array containing the arguments to be passed to
  clojure.main/-main function."
  [^Symbol main-ns ^File target-dir ^IPersistentMap compiler-opts]
  (into-array Object [(into-array String
                                  ["-e"
                                   (pr-str
                                    `(binding [*compile-path* ~(.getPath target-dir)
                                               *compiler-options* ~compiler-opts]
                                       (clojure.core/compile
                                        (symbol ~(name main-ns)))))])]))

(defn- compile*
  "Compiles the main-ns by writing compiled .class files to target-dir.

  Displays a spin animation during the process."
  [^Symbol main-ns ^Sequential classpath ^File target-dir ^IPersistentMap compiler-opts]
  (let [urls         (into-array URL (map #(.toURL %) (conj classpath target-dir)))
        class-loader (URLClassLoader. urls (.. ClassLoader getSystemClassLoader getParent))
        clojure      (.loadClass class-loader "clojure.main")
        main         (.getDeclaredMethod clojure "main" (into-array Class  [(.getClass (make-array String 0))]))
        _            (misc/log :info "Compiling %s..." main-ns)
        spin         (spinner/create-and-start!)]
    (try
      @(future
         (let [current-class-loader (.. Thread currentThread (getContextClassLoader))]
           (.. Thread currentThread (setContextClassLoader class-loader))
           (.invoke main nil (main-args main-ns target-dir compiler-opts))
           (.. Thread currentThread (setContextClassLoader current-class-loader))))
      (catch Throwable t
        (rethrow-compilation-error main-ns t))
      (finally
        (.. Thread currentThread (getContextClassLoader))
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
  (let [namespaces  (find-namespaces-on-classpath classpath)
        _           (compile* main-ns classpath target-dir compiler-opts)]
    (reduce (fn [result ^File class-file]
              (assoc result class-file
                     (get-class-file-source namespaces (misc/relativize class-file target-dir))))
            {}
            (misc/filter-files (file-seq target-dir)))))

(defn- merge-data-readers
  [^InputStream src ^File target]
  (let [new-data-readers (misc/read-edn src)
        old-data-readers (when (misc/file-exists? target) (misc/read-edn target))]
    (->> (merge old-data-readers new-data-readers)
         pr-str
         (spit target))))

(defn- data-readers-file?
  "Returns true if the java.io.File object represents a Clojure data-readers
  (data_readers.clj or data_readers.cljc)."
  [^File file]
  (re-find #"^data_readers\.cljc?$"
           (.getPath file)))

(defn- clojure-file?
  "Returns true if the java.io.File object represents a Clojure source file."
  [^File file]
  (let [^String file-name (.getName file)]
    (some #(string/ends-with? file-name %)
          namespace.file/clojure-extensions)))

(defn- ^File copy
  "Copies source to target and returns it (the target file).

  Gives a special treatment to data-readers, by merging multiple ones
  into their respective files (data_readers.clj or
  data_readers.cljc)."
  [^InputStream source ^File target ^File base-dir]
  (when (or (data-readers-file? target)
            (not (clojure-file? target)))
    (if (data-readers-file? target)
      (merge-data-readers source target)
      (do (io/make-parents target)
          (io/copy source target)
          target))))

(defn- copy-files-from-jar
  "Copies files from within the jar file to the target directory."
  [^File jar ^File target-dir]
  (with-open [jar-file (JarFile. jar)]
    (->> jar-file
         .entries
         enumeration-seq
         (mapv (fn [^JarEntry jar-entry]
                 (let [^File target-file (io/file target-dir (.getName jar-entry))]
                   (when-not (.isDirectory jar-entry)
                     (copy (.getInputStream jar-file jar-entry) target-file target-dir))))))))

(defn- copy-files-from-dir
  "Copies files (typically resources) from source to target."
  [^File src ^File target-dir]
  (->> src
       file-seq
       misc/filter-files
       (mapv (fn [^File file]
               (let [target-file (io/file target-dir (misc/relativize file src))]
                 (copy (io/input-stream file) target-file target-dir))))))

(defn- copy-files*
  [^File src ^File target-dir]
  (if (.isDirectory src)
    (copy-files-from-dir src target-dir)
    (copy-files-from-jar src target-dir)))

(defn copy-files
  "Iterates over the files (typically directories and jar files) by
  copying their content to the target directory. Data
  readers (declared in data_readers.clj or data_readers.cljc in the
  root of the classpath) are merged together into their respective
  files.

  Returns a map of target files to their sources."
  [^ISeq classpath ^File target-dir]
  (->> classpath
       (mapcat #(map vector (remove nil? (copy-files* % target-dir)) (repeat %)))
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
  target files to their sources. This data structure aims Vessel to determine
  which files belong to each application layer during the containerization
  process.

  See also: vessel.clojure.classpath and vessel.reader."
  [^IPersistentMap manifest ^Sequential deps ^File target-dir]
  (let [{::v1/keys [compiler-opts, main-ns, resource-paths, source-paths]} manifest
        ^ISeq classpath                                                     (concat source-paths resource-paths deps)
        ^IPersistentMap classes (compile main-ns classpath target-dir compiler-opts)
        ^ISeq paths-to-lookup-remaining-files (into resource-paths deps)
        ^IPersistentMap remaining-files (copy-files paths-to-lookup-remaining-files target-dir)]
    (merge classes remaining-files)))

(defn- write-bytes
  "Writes the content of the supplied file to the jar file being created."
  [^JarOutputStream jar-stream ^File file-to-write]
  (let [input (BufferedInputStream. (FileInputStream. file-to-write))
        buffer (byte-array 1024)]
    (loop []
      (let [count (.read input buffer)]
        (when-not (< count 0)
          (.write jar-stream buffer 0 count))))))

(defn- write-jar-entry
  "Writes the supplied file to the jar being created. The base-dir is used to
  properly resolve the path of the jar entry within the jar file."
  [^JarOutputStream jar-stream ^File file-to-write ^File base-dir]
  (let [^String path-within-jar (.getPath (misc/relativize file-to-write base-dir))
        ^JarEntry jar-entry (JarEntry. path-within-jar)]
    (.setLastModifiedTime jar-entry
                          (FileTime/from (misc/last-modified-time file-to-write)))
    (.putNextEntry jar-stream jar-entry)
    (write-bytes jar-stream file-to-write)
    (.closeEntry jar-stream)))

(defn ^File bundle-up
  ""
  [^File jar-path ^Sequential files-to-be-bundled ^File base-dir]
  (with-open [jar-stream (JarOutputStream. (BufferedOutputStream. (FileOutputStream. jar-path)))]
    (loop [files files-to-be-bundled]
      (let [^File next-entry (first files)]
        (if-not next-entry
          (do (.finish jar-stream)
              jar-path)
          (do
            (write-jar-entry jar-stream next-entry base-dir)
            (recur (next files))))))))
