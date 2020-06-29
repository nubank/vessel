(ns vessel.clojure.builder
  "Builder for Clojure applications."
  (:refer-clojure :exclude [compile])
  (:require [clojure.java.classpath :as java.classpath]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as string]
            [clojure.tools.namespace.find :as namespace.find]
            [spinner.core :as spinner]
            [vessel.misc :as misc])
  (:import [clojure.lang ISeq IPersistentMap Symbol]
           [java.io File InputStream]
           [java.net URL URLClassLoader]
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
  [^Symbol main-ns ^File target-dir]
  (into-array Object [(into-array String
                                  ["-e"
                                   (format "(binding [*compile-path* \"%s\"]
(clojure.core/compile '%s))"
                                           target-dir (pr-str main-ns))])]))

(defn- compile*
  "Compiles the main-ns by writing compiled .class files to target-dir.

  Displays a spin animation during the process."
  [^Symbol main-ns ^File target-dir classpath]
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
           (.invoke main nil (main-args main-ns target-dir))
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
  [classpath ^Symbol main-ns ^File target-dir]
  (let [namespaces  (find-namespaces-on-classpath classpath)
        classes-dir (misc/make-dir target-dir "classes")
        _           (compile* main-ns classes-dir classpath)]
    (reduce (fn [result ^File class-file]
              (assoc result class-file
                     (get-class-file-source namespaces (misc/relativize class-file classes-dir))))
            {}
            (misc/filter-files (file-seq classes-dir)))))

(defn copy-libs
  "Copies libraries that don't contain Clojure sources or that aren't directly
  referenced by the the application being built to the directory lib under the
  target dir.

  Returns a map of java.io.File to java.io.File mapping the target location of
  each library to its source."
  [libs ^File target-dir]
  (let [lib-dir (misc/make-dir target-dir "lib")]
    (reduce (fn                                                                                                                          [result ^File lib]
              (let [^File dest-file (io/file lib-dir (.getName lib))]
                (io/copy lib dest-file)
                (assoc result dest-file lib)))
            {} libs)))

(defn- merge-data-readers
  [^InputStream src ^File target]
  (let [new-data-readers (misc/read-edn src)
        old-data-readers (when (misc/file-exists? target) (misc/read-edn target))]
    (->> (merge old-data-readers new-data-readers)
         pr-str
         (spit target))))

(defn- ^File copy
  "Copies src to target and returns the later one.

  Gives a special treatment to data-readers, by merging multiple ones
  into their respective files (data_readers.clj or
  data_readers.cljc)."
  [^InputStream src ^File target ^File base-dir]
  (let [file-path (.getPath (misc/relativize target base-dir))]
    (if (re-find #"^data_readers\.cljc?$" file-path)
      (merge-data-readers src target)
      (do (io/make-parents target)
          (io/copy src target)))
    target))

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
  [classpath ^File target-dir]
  (let [classes-dir (misc/make-dir target-dir "classes")]
    (->> classpath
         (mapcat #(map vector (remove nil? (copy-files* % classes-dir)) (repeat %)))
         (into {}))))

(defn ^IPersistentMap build-application
  "Builds a Clojure application.

  Compiles ahead-of-time (AOT) all Clojure sources present in the application's
  classpath that the supplied main ns depends on. Writes resulting .class files
  to the directory classes under the supplied target directory along with any
  resource files found in the classpath. Also copies remaining libraries (jar
  files) referenced by the application in question to the directory lib under
  the supplied target directory.

  Options is a persistent map containing the following meaningful keys:

  :deps ISeq of java.io.File

  A sequence of file objects representing all dependencies of the
  application. See also vessel.clojure.classpath/assemble-deps.

  :main-ns Symbol

  A symbol representing the aplication's main namespace. It must contains a
  :gen-class directive.

  :resource-paths ISeq of java.io.File

  The application's resource paths.

    :source-paths ISeq of java.io.File

  The application's source paths.

  :target-dir java.io.File

  Directory to write compiled classes and libraries to.

  Returns a persistent map containing two keys: :clojure.application/classes and
  :clojure.application/lib. They are maps of java.io.File to java.io.File
  objects mapping target files to their sources. This data structure aims Vessel
  to determine which files belong to each application layer during the
  containerization process."
  [^IPersistentMap options]
  (let [{:keys [deps main-ns resource-paths source-paths target-dir]} options
        ^ISeq classpath                                                     (concat source-paths resource-paths deps)
        ^IPersistentMap classes (compile classpath main-ns target-dir)
        ^ISeq paths-to-lookup-resources                                     (into resource-paths (vals classes))
        ^IPersistentMap resources (copy-files paths-to-lookup-resources target-dir)
        ^ISeq libs                                                          (misc/filter-files (set/difference (set deps) (set paths-to-lookup-resources)))]
    #:clojure.application{:classes (merge classes resources)
                          :lib     (copy-libs libs target-dir)}))
