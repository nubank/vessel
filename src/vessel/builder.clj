(ns vessel.builder
  "Builder for Clojure applications."
  (:refer-clojure :exclude [compile])
  (:require [clojure.java.classpath :as java.classpath]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as string]
            [clojure.tools.namespace.find :as namespace.find]
            [spinner.core :as spinner]
            [vessel.misc :as misc])
  (:import clojure.lang.Symbol
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
  "Given a map from ns symbols to their sources (either directories or
  jar files on the classpath) and a file representing a compiled
  class, returns the source where the class in question comes from.

  This function must always return a known source for the .class file
  in question. If it can't determine the source, it throws an
  IllegalStateException."
  [namespaces ^File class-file]
  (let [ns (class-file->ns class-file)]
    (or (get namespaces ns)
        (get namespaces (parent-ns ns))
        (throw (IllegalStateException. (str "Unknown source for .class file " class-file))))))

(defn- rethrow-compilation-error
  "Unwrap the actual compilation exception and rethrow it as an
  ExceptionInfo that will be properly handled by Vessel."
  [^Symbol main-class throwable]
  (throw (ex-info (str "Failed to compile " main-class)
                  #:vessel.error{:category :vessel/compilation-error
                                 :throwable (ex-cause (ex-cause throwable))})))

(defn- main-args
  "Returns a Java array containing the arguments to be passed to
  clojure.main/-main function."
  [^Symbol main-class ^File target-dir]
  (into-array Object [(into-array String
                                  ["-e"
                                   (format "(binding [*compile-path* \"%s\"]
(clojure.core/compile '%s))"
                                           target-dir (pr-str main-class))])]))

(defn- compile*
  "Compiles the main-class writing compiled .class files to target-dir.

  Displays a spin animation during the process."
  [^Symbol main-class ^File target-dir classpath-files]
  (let [urls (into-array URL (map #(.toURL %) classpath-files))
        class-loader (URLClassLoader. urls (.. ClassLoader getSystemClassLoader getParent))
        clojure (.loadClass class-loader "clojure.main")
        main (.getDeclaredMethod clojure "main" (into-array Class  [(.getClass (make-array String 0))]))
        _ (misc/log :info "Compiling %s..." main-class)
        spin (spinner/create-and-start!)]
    (try
      @(future
         (binding [*out* (java.io.StringWriter.)]
           (.. Thread currentThread (setContextClassLoader class-loader))
           (.invoke main nil (main-args main-class target-dir))))
      (catch Throwable t
        (rethrow-compilation-error main-class t))
      (finally
        (spinner/stop! spin)
        (println)))))

(defn- find-namespaces
  "Returns all namespaces declared within the file (either a directory
  or a jar file)."
  [^File file]
  (cond
    (.isDirectory file) (namespace.find/find-namespaces-in-dir file)
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

  Returns a map from compiled class files (as instances of
  java.io.File) to their sources (instances of java.io.File as well
  representing directories or jar files on the classpath)."
  [classpath-files ^Symbol main-class ^File target-dir]
  (let [namespaces (find-namespaces-on-classpath classpath-files)
        classes-dir (misc/make-dir target-dir "classes")
        _ (compile* main-class classes-dir classpath-files)]
    (reduce (fn [result ^File class-file]
              (assoc result class-file
                     (get-class-file-source namespaces (misc/relativize class-file classes-dir))))
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
              dest-file))
          libs)))

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

  Returns a map from target files to their sources."
  [classpath-files ^File target-dir]
  (let [classes-dir (misc/make-dir target-dir "classes")]
    (->> classpath-files
         (mapcat #(map vector (remove nil? (copy-files* % classes-dir)) (repeat %)))
         (into {}))))

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

  * :classpath-files (set of java.io.File objects) representing the
  classpath of the Clojure application;
  * :main-class (symbol) application entrypoint containing
  a :gen-class directive and a -main function;
  * :resource-paths (set of java.io.File objects) a set of paths
  containing resource files of the Clojure application;
  * :target-dir (java.io.File) an existing directory where the
  application's files will be written to.

  Returns a map containing the following namespaced keys:
  * :app/classes - a map from target files (compiled class files and
  resources) to their sources (either directories or jar files present
  on the classpath);
  * :app/lib - a sequence of java.io.File objects containing libraries
  that the application depends on."
  [{:keys [classpath-files ^Symbol main-class resource-paths ^File target-dir]}]
  {:pre [classpath-files main-class target-dir]}
  (let [web-inf (misc/make-dir target-dir "WEB-INF")
        classes (compile classpath-files main-class web-inf)
        dirs+jar-files (set/union resource-paths (set (vals classes)))
        libs (misc/filter-files (set/difference classpath-files dirs+jar-files))
        resource-files (copy-files dirs+jar-files web-inf)]
    #:app{:classes (merge classes resource-files)
          :lib (copy-libs libs web-inf)}))
