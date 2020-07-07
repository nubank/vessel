(ns vessel.reader
  "Reader for Vessel manifests."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.string :as string]
            [clojure.walk :as walk]
            [vessel.misc :as misc]
            [vessel.v1 :as v1]
            [vessel.hashing :as hashing])
  (:import [clojure.lang IPersistentMap IPersistentVector Keyword Symbol]
           java.io.File
           java.util.regex.Pattern))

(defn- validate-with-spec
  "Validates the manifest's structure using the spec :vessel.v1/manifest.

  Returns the manifest untouched if it matches the spec or throws an exception
  containing the problems otherwise."
  [^IPersistentMap manifest]
  (if-let [problems (s/explain-data ::v1/manifest manifest)]
    (throw (ex-info "Invalid manifest. See further details about the violations below:" problems))
    manifest))

(defn- ^String expand-variable
  "Takes a string s and a map of variables passed to Vessel at
  build-time. Expands variables in the form {{var-name}} by replacing them with
  matching values taken from the build-time variables map.

  Variables have two forms:

  {{var-name}}
  {{var-name|default-value}}

  In the second form, if the build-time-variables map doesn't contain a key
  :var-name, the string \"default-value\" will be used. If a matching value
  can't be found for a given variable name and there is no a default value for
  it, throws an exception with a descriptive error message."
  [^String s ^IPersistentMap build-time-variables]
  (letfn [(explain-mismatch [^String variable ^Keyword k]
            (format "No matching value found for variable \"%s\" at the build-time variables.
Did you forget to pass --build-arg %s=<value> to Vessel?"
                    variable (name k)))]
    (string/replace s #"\{\{([^\}]+)\}\}" (fn [match]
                                            (let [^String variable (string/trim (last match))
                                                  [k d]            (string/split variable #"\|")
                                                  ^Keyword key     (keyword (edn/read-string k))
                                                  default-value    (when d
                                                                     (string/trim d))]
                                              (str (or (get build-time-variables key default-value)
                                                       (throw (ex-info (explain-mismatch variable key)
                                                                       {:variable             variable
                                                                        :build-time-variables build-time-variables})))))))))

(def non-expandable-keys
  "Those keys don't accept variables because if they did, builds wouldn't be
  deterministic and the Vessel's extended cache wouldn't work properly."
  [::v1/app-root ::v1/classifiers  ::v1/resource-paths ::v1/source-paths])

(defn- expand-all-variables
  "Traverses the manifest map by expanding variables with matching arguments
  passed to Vessel at build-time."
  [^IPersistentMap manifest ^IPersistentMap build-time-variables]
  (let [reduced-manifest (apply dissoc manifest non-expandable-keys)]
    (merge manifest
           (walk/postwalk #(if (string? %)
                             (expand-variable % build-time-variables)
                             %)
                          reduced-manifest))))

(defn- ^String derive-app-root
  "Derives the app-root from the main-ns."
  [^Symbol main-ns]
  (let [split-at-dots #(string/split % #"\.")]
    (->> main-ns
         str
         split-at-dots
         (drop-last 1)
         (string/join "/")
         (str "/"))))

(defn- merge-defaults
  "Merge default values into the supplied manifest map."
  [^IPersistentMap manifest]
  (let [{::v1/keys [main-ns]} manifest
        app-root              (derive-app-root main-ns)
        defaults              #::v1 {:app-root       app-root
                                     :app-type       :jar
                                     :resource-paths #{}}]
    (merge defaults manifest)))

(def hashable-keys
  "Those keys are taken in consideration to calculate the manifest's id (a
  sha256 digest). They are part of this calculus because they affect how layers
  are organized."
  (conj non-expandable-keys ::v1/app-type ::v1/main-ns))

(defn- add-manifest-id
  "Assoc's the sha256 that identifies the manifest in question into it as
  :vessel.v1/id."
  [^IPersistentMap manifest]
  (assoc manifest ::v1/id
         (hashing/sha256 (select-keys manifest hashable-keys))))

(defn- ^File ensure-file
  "Turns the string x into a java.io.File object that satisfies the supplied
  options.

  Options is a map that control which validations should be performed and how
  the conversion to a java.io.File occurs. The following boolean options are
  valid: :absolute - checks if the file or directory in question is an absolute
  path.

  :canonicalize - returns a canonical file object.

  :directory - checks if the file in question is a directory.

  :exists - checks if the file or directory exists.

  Throws an exception with a meaningful message if one of the aforementioned
  validations fails."
  [{:keys [absolute, canonicalize, directory, exists]} path ^String x]
  (let [file (io/file x)
        s    (print-str path)
        data {:path path :value x}]
    (when (and exists (not (misc/file-exists? file)))
      (throw (ex-info (format "Invalid path at %s - no such file or directory: \"%s\"." s x)
                      data)))
    (when (and absolute (not (misc/absolute-file? file)))
      (throw (ex-info (format "Invalid path at %s - \"%s\" isn't an absolute path." s x)
                      data)))
    (when (and directory (not (misc/directory? file)))
      (throw (ex-info (format "Invalid path at %s - \"%s\" isn't a directory." s x)
                      data)))
    (if canonicalize
      (misc/canonicalize-file file)
      file)))

(defn- ^Pattern ensure-regex
  "Takes a string x and turns it into a java.util.regex.Pattern. Throws an
  exception with a meaningful message if the string doesn't represent a valid
  Java regex."
  [^IPersistentVector path ^String x]
  (try
    (re-pattern x)
    (catch java.util.regex.PatternSyntaxException e
      (throw (ex-info (format "Invalid regex %s at %s - %s" x (print-str path) (.getMessage e))
                      {:path path :value x})))))

(defn- transform-values
  "Transforms some manifest values into higher level objects."
  [^IPersistentMap manifest]
  (-> manifest
      (update ::v1/app-root (partial ensure-file {:absolute true} ::v1/app-root))
      (update ::v1/source-paths #(into #{} (map (partial ensure-file {:canonicalize true, :directory true, :exists true} ::v1/source-paths) %)))
      (update ::v1/resource-paths #(into #{} (map (partial ensure-file {:canonicalize true, :directory true, :exists true} ::v1/resource-paths) %)))
      (update ::v1/extra-paths #(mapv (fn [{:keys [from, to] :as copyable}]
                                        (assoc copyable :from (ensure-file {:canonicalize true, :exists true} [::v1/extra-paths :from] from)
                                               :to (ensure-file {:absolute true} [::v1/extra-paths :to] to)))
                                      %))
      (update ::v1/classifiers #(into {} (map (fn [[k v]]
                                                [k (ensure-regex [::v1/classifiers k] v)])
                                              %)))))

(defn ^IPersistentMap read-manifest
  "Reads, validates and parses a Vessel manifest.

  Manifests are regular EDN files containing various attributes that control how
  Vessel will containerize a Clojure application. The manifest structure is
  defined by the spec :vessel.v1/manifest."
  ([^File manifest]
   (read-manifest manifest {}))
  ([^File manifest ^IPersistentMap build-time-variables]
   (-> (misc/read-edn manifest)
       validate-with-spec
       (expand-all-variables build-time-variables)
       merge-defaults
       add-manifest-id
       transform-values)))
