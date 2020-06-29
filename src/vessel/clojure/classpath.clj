(ns vessel.clojure.classpath
  "Agnostic classpath assembler for project dependencies built on top of
  clojure.tools.deps.alpha.

  It assembles classpaths uniformly for dependencies declared in deps.edn and
  project.clj (Leiningen) files."
  (:require [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.string :as string]
            [clojure.tools.deps.alpha :as deps]
            [vessel.misc :as misc]
            [vessel.v1 :as v1])
  (:import java.io.File))

(def maven-repositories
  "Standard repositories for Maven and Clojure libraries. They're
  declared in clojure.tools.deps.alpha.util.maven as well and are
  replicated here for clarity."
  {"central" {:url "https://repo1.maven.org/maven2/"}
   "clojars" {:url "https://repo.clojars.org/"}})

(defn- make-classpath
  "Takes a deps configuration map as the one taken by
  clojure.tools.deps.alpha/resolve-deps and returns a sequence of files and
  directories that describe the classpath for declared dependencies."
  [deps-map]
  (let [mapvf (partial mapv io/file)]
    (-> deps-map
        (select-keys [:deps :mvn/repos]) ;; select relevant keys
        (update :mvn/repos (partial merge maven-repositories))
        (deps/resolve-deps nil)
        (deps/make-classpath nil nil)
        (string/split #":")
        mapvf)))

(defn read-leiningen-project
  "Reads a Leiningen project.clj file and returns it as a map."
  [^File project]
  (binding [*read-eval* false]
    (->> project
         slurp
         (format "(%s)")
         read-string
         (filter #(= 'defproject (first %)))
         first
         rest
         (apply hash-map))))

(defn canonicalize-s3-urls
  "Takes a map of Maven repositories and convert Amazon S3 URLs to a canonical
  form that can be used by clojure.tools.deps.alpha.

  This canonicalization is needed because s3-wagon-private requires an URL in
  the form s3p://."
  [mvn-repos]
  (misc/map-vals (fn [repo]
                   (update repo :url #(string/replace % #"^s3p://" "s3://")))
                 mvn-repos))

(defn leiningen-project->deps-map
  "Takes a Leiningen project map and turn it into a deps configuration map as
  expected by clojure.tools.deps.alpha."
  [{:keys [dependencies repositories]}]
  {:mvn/repos
   (canonicalize-s3-urls (into {} repositories))
   :deps (reduce (fn [deps [lib version & others]]
                   (assoc deps lib
                          (merge {:mvn/version version}
                                 (apply hash-map others))))
                 {} dependencies)})

(defmulti ^{:doc "assemble-depss classpaths for projects managed by tools.deps.alpha
and/or Leiningen in an uniform way.

Takes a map containing the keys :clojure/tool (nowadays, :leiningen or
:tools.deps.alpha) and :project/descriptor (a java.io.File) and returns a
sequence of java.io.File objects describing the project's classpath. The
classpath only contains project dependencies since root directories such as src
and resources must be supplied as inputs for Vessel."}
  assemble-deps :clojure/tool)

(s/fdef assemble-deps
  :args (s/cat :options (s/keys :req [:clojure/tool :project/descriptor]))
  :ret (s/coll-of v1/file?))

(defmethod assemble-deps :leiningen
  [{:project/keys [descriptor]}]
  (-> descriptor
      read-leiningen-project
      leiningen-project->deps-map
      make-classpath))

(defmethod assemble-deps :tools.deps.alpha
  [{:project/keys [descriptor]}]
  (make-classpath (misc/read-edn descriptor)))
