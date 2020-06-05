(ns vessel.jib.containerizer
  "Containerization API built on top of Google Jib."
  (:require [vessel.jib.cache :as jib.cache]
            [vessel.jib.credentials :as credentials]
            [vessel.jib.helpers :as jib.helpers]
            [vessel.misc :as misc])
  (:import [com.google.cloud.tools.jib.api Containerizer ImageReference Jib JibContainer JibContainerBuilder LogEvent RegistryImage TarImage]
           [com.google.cloud.tools.jib.api.buildplan FileEntriesLayer FileEntriesLayer$Builder FileEntry FilePermissions ImageFormat]
           [com.google.cloud.tools.jib.builder.steps PreparedLayer PreparedLayer$StateInTarget StepsRunner]
           [com.google.cloud.tools.jib.cache Cache CachedLayer]
           com.google.cloud.tools.jib.configuration.BuildContext
           com.google.cloud.tools.jib.event.events.ProgressEvent
           com.google.cloud.tools.jib.image.Layer
           [java.lang.reflect Constructor Field]
           java.nio.file.attribute.PosixFilePermission
           [java.util.concurrent Callable ExecutorService]
           java.util.function.Function
           java.util.List))

(defn- ^Field get-private-field
  "Given an object and the name of a private field, returns it as a
  java.lang.reflect.Field instance.

  The field is set to be accessible. Thus subsequent calls to the
  methods get and set will succeed."
  [object ^String field-name]
  (let [^Field field (.. object getClass (getDeclaredField field-name))]
    (.setAccessible field true)
    field))

(defn- ^PreparedLayer make-prepared-layer
  "Creates a new PreparedLayer instance via reflection since this class
  is package-private and its members are inaccessible."
  [^CachedLayer layer ^String layer-name]
  (let [^Constructor constructor (.getDeclaredConstructor PreparedLayer (into-array Class  [Layer String PreparedLayer$StateInTarget]))
        unknown                  (first (filter #(= "UNKNOWN" (str %)) (.getEnumConstants PreparedLayer$StateInTarget)))]
    (.setAccessible constructor true)
    (.newInstance constructor (into-array Object [layer layer-name unknown]))))

(defn- retrieve-cached-layer-step
  "Emulates a step from the package
  com.google.cloud.tools.jib.builder.steps that retrieves the cached
  layer whose name and digest are given.

  Returns an instance of java.util.concurrent.Callable interface that
  returns a PreparedLayer object representing the cached layer in
  question."
  [^BuildContext build-context {:image.layer/keys [name digest]}]
  (reify Callable
    (call [this]
      (let [^Cache cache              (.getApplicationLayersCache build-context)
            ^CachedLayer cached-layer (jib.cache/cached-layer-for-digest cache digest)]
        (make-prepared-layer cached-layer name)))))

(defn- retrieve-cached-layers
  "Creates a java.lang.Runnable instance that alters the supplied
  StepsRunner object by adding additional cached layers."
  [^BuildContext build-context ^StepsRunner steps-runner cached-layers]
  (reify Runnable
    (run [this]
      (let [^ExecutorService executor-service (.getExecutorService build-context)
            steps-results                     (.. (get-private-field steps-runner "results") (get steps-runner))
            application-layers-field          (get-private-field steps-results "applicationLayers")
            application-layers                (.get application-layers-field steps-results)
            additional-cached-layers          (map #(.submit executor-service (retrieve-cached-layer-step build-context %)) cached-layers)]
        (.set application-layers-field steps-results (into  additional-cached-layers application-layers))))))

(defn- ^Function wrap-steps-runner-factory
  "Wraps the supplied steps runner factory into a function that alters
  the StepsRunner returned by it.

  The main goal of this wrapper is to inject a custom step into the
  list of steps held by the StepsRunner object in question. This
  custom step retrieves layers from the cache based on the information
  provided by the build-planner."
  [^Function steps-runner-factory cached-layers]
  (misc/java-function (fn [^BuildContext build-context]
                        (let [^StepsRunner steps-runner (.apply steps-runner-factory build-context)
                              ^List steps-to-run        (.. (get-private-field steps-runner "stepsToRun") (get steps-runner))]
                          (.add steps-to-run 3 (retrieve-cached-layers build-context steps-runner cached-layers))
                          steps-runner))))

(defn- ^Containerizer instrumented-containerizer
  "Instruments the supplied containerizer by replacing its steps runner
  factory with a wrapper tha inject custom cached layers into the
  image being built."
  [^Containerizer containerizer cached-layers]
  (let [^Field field                   (get-private-field containerizer "stepsRunnerFactory")
        ^Function steps-runner-factory (.get field containerizer)]
    (.set field containerizer (wrap-steps-runner-factory steps-runner-factory cached-layers))
    containerizer))

(defn   ^ImageReference make-image-reference
  "Returns a new image reference from the provided values."
  [{:image/keys [^String registry ^String repository ^String tag]}]
  (ImageReference/of registry repository tag))

(defn- ^Containerizer make-containerizer
  "Makes a new Jib containerizer object to containerize the application
  to a given tarball."
  [{:image/keys [name layers tar-path]}]
  (let [cache-dir                    (-> (misc/home-dir)
                                         (misc/make-dir ".vessel-cache")
                                         str
                                         misc/string->java-path)
        handler-name                 "vessel.jib.containerizer"
        ^Containerizer containerizer (.. Containerizer
                                         (to (.. TarImage (at (misc/string->java-path tar-path))
                                                 (named (make-image-reference name))))
                                         (setBaseImageLayersCache cache-dir)
                                         (setApplicationLayersCache cache-dir)
                                         (setToolName "vessel")
                                         (addEventHandler LogEvent (jib.helpers/log-event-handler handler-name))
                                         (addEventHandler ProgressEvent (jib.helpers/progress-event-handler handler-name)))
        cached-layers                (filter #(= :cached-layer (:image.layer/kind %)) layers)]
    (if (seq cached-layers)
      (instrumented-containerizer containerizer cached-layers)
      containerizer)))

(defn- containerize*
  [^JibContainerBuilder container-builder image-spec]
  (.containerize container-builder (make-containerizer image-spec)))

(defn- ^FileEntry make-file-entry
  "Creates a new FileEntry object from the supplied values."
  [{:layer.entry/keys [source target file-permissions modification-time]}]
  (let [permissions (some->> file-permissions
                             (map #(PosixFilePermission/valueOf %))
                             set
                             (FilePermissions/fromPosixFilePermissions))]
    (FileEntry. (misc/string->java-path source)
                (jib.helpers/string->absolute-unix-path target)
                (or permissions FilePermissions/DEFAULT_FILE_PERMISSIONS)
                modification-time)))

(defn- ^FileEntriesLayer make-file-entries-layer
  "Makes a FileEntriesLayer object from the supplied data structure."
  [{:image.layer/keys [name entries]}]
  (loop [^FileEntriesLayer$Builder layer (.. FileEntriesLayer builder (setName name))
         entries                         entries]
    (if-not (seq entries)
      (.build layer)
      (let [layer-entry (first entries)]
        (.addEntry layer (make-file-entry layer-entry))
        (recur layer (rest entries))))))

(defn- make-file-entries-layers
  "Returns a sequence of FileEntriesLayer objects for those layers in
  the build plan whose :image.layer/kind is :file-entry."
  [{:image/keys [layers]}]
  (keep (fn [layer]
          (when (= :file-entry (:image.layer/kind layer))
            (make-file-entries-layer layer)))
        layers))

(defn-   ^RegistryImage make-registry-image
  "Given an ImageReference instance, returns a new registry image
  object."
  [^ImageReference image-reference]
  (let [^CredentialRetriever retriever (credentials/retriever-chain image-reference)]
    (.. RegistryImage (named image-reference)
        (addCredentialRetriever retriever))))

(defn-   ^Boolean is-in-docker-hub?
  "Is the image in question stored in the official Docker hub?"
  [^ImageReference image-reference]
  (= "registry-1.docker.io"
     (.getRegistry image-reference)))

(defn-   ^JibContainerBuilder make-container-builder
  "Returns a new container builder to start building the image.

  from is a map representing the base image descriptor. The following
  keys are meaningful: :image/registry, :image/repository
  and :image/tag. The :image/registry and :image/tag are optional."
  [from]
  (let [^ImageReference reference (make-image-reference from)]
    (.. Jib (from
             (if (is-in-docker-hub? reference)
               (str reference)
               (make-registry-image reference)))
        (setCreationTime (misc/now))
        (setFormat ImageFormat/Docker))))

(defn containerize
  "Given an image spec, containerize the application in question by
  producing a tarball containing image layers and metadata files."
  [{:image/keys [from layers] :as image-spec}]
  {:pre [from layers]}
  (let [file-entries-layers     (make-file-entries-layers image-spec)
        ^JibContainer container (-> (make-container-builder from)
                                    (.setFileEntriesLayers file-entries-layers)
                                    (containerize* image-spec))]
    {:image/reference         (str (.getTargetImage container))
     :image/digest            (str (.getDigest container))
     :jib/file-entries-layers file-entries-layers}))
