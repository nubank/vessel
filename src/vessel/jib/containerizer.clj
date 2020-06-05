(ns vessel.jib.containerizer
  "Containerization API built on top of Google Jib."
  (:require [vessel.jib.cache :as jib.cache]
            [vessel.jib.credentials :as jib.credentials]
            [vessel.jib.helpers :as jib.helpers]
            [vessel.misc :as misc])
  (:import [clojure.lang IPersistentMap ISeq]
           [com.google.cloud.tools.jib.api Containerizer ImageReference Jib JibContainer JibContainerBuilder LogEvent RegistryImage TarImage]
           [com.google.cloud.tools.jib.api.buildplan FileEntriesLayer FileEntriesLayer$Builder FileEntry FilePermissions ImageFormat]
           [com.google.cloud.tools.jib.builder.steps PreparedLayer PreparedLayer$StateInTarget StepsRunner]
           [com.google.cloud.tools.jib.cache Cache CachedLayer]
           com.google.cloud.tools.jib.configuration.BuildContext
           com.google.cloud.tools.jib.event.events.ProgressEvent
           com.google.cloud.tools.jib.image.Layer
           java.io.File
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

(defn- ^Callable retrieve-cached-layers-step
  "Emulates a step from the package
  com.google.cloud.tools.jib.builder.steps that retrieves the cached
  layer whose name and digest are given.

  Returns an instance of java.util.concurrent.Callable interface that
  returns a PreparedLayer object representing the cached layer in
  question."
  [^BuildContext build-context {:layer/keys [id digest]}]
  (reify Callable
    (call [this]
      (let [^Cache cache              (.getApplicationLayersCache build-context)
            ^CachedLayer cached-layer (jib.cache/get-cached-layer-by-digest cache digest)]
        (make-prepared-layer cached-layer (name id))))))

(defn- ^Runnable retrieve-cached-layers
  "Creates a java.lang.Runnable instance that alters the supplied
  StepsRunner object by adding additional cached layers."
  [^BuildContext build-context ^StepsRunner steps-runner ^ISeq cached-layers]
  (reify Runnable
    (run [this]
      (let [^ExecutorService executor-service (.getExecutorService build-context)
            steps-results                     (.. (get-private-field steps-runner "results") (get steps-runner))
            application-layers-field          (get-private-field steps-results "applicationLayers")
            application-layers                (.get application-layers-field steps-results)
            additional-cached-layers          (map #(.submit executor-service (retrieve-cached-layers-step build-context %)) cached-layers)]
        (.set application-layers-field steps-results (into  additional-cached-layers application-layers))))))

(defn- ^Function wrap-steps-runner-factory
  "Wraps the supplied steps runner factory into a function that alters the
  StepsRunner returned by it.

  The main goal of this wrapper is to inject a custom step into the list of
  steps held by the StepsRunner object in question. This custom step retrieves
  cached layers from the extended cache maintained by Vessel d based on the
  information provided by the build plan."
  [^Function steps-runner-factory cached-layers]
  (misc/java-function (fn [^BuildContext build-context]
                        (let [^StepsRunner steps-runner (.apply steps-runner-factory build-context)
                              ^List steps-to-run        (.. (get-private-field steps-runner "stepsToRun") (get steps-runner))]
                          (.add steps-to-run 3 (retrieve-cached-layers build-context steps-runner cached-layers))
                          steps-runner))))

(defn- ^Containerizer instrumented-containerizer
  "Instruments the supplied containerizer by replacing its steps runner
  factory with a wrapper that injects custom cached layers into the
  image being built."
  [^Containerizer containerizer ^ISeq cached-layers]
  (let [^Field field                   (get-private-field containerizer "stepsRunnerFactory")
        ^Function steps-runner-factory (.get field containerizer)]
    (.set field containerizer (wrap-steps-runner-factory steps-runner-factory cached-layers))
    containerizer))

(defn- ^Containerizer make-containerizer
  "Makes a new Jib containerizer object to containerize the application
  to a given tarball."
  [{:image/keys [layers target]} {:keys [cache-dir  tar-path]}]
  (let [cache-dir-path               (.toPath cache-dir)
        handler-name                 "vessel.jib.containerizer"
        ^Containerizer containerizer (.. Containerizer
                                         (to (.. TarImage (at (.toPath tar-path))
                                                 (named (ImageReference/parse target))))
                                         (setBaseImageLayersCache cache-dir-path)
                                         (setApplicationLayersCache cache-dir-path)
                                         (setToolName "vessel")
                                         (addEventHandler LogEvent (jib.helpers/log-event-handler handler-name))
                                         (addEventHandler ProgressEvent (jib.helpers/progress-event-handler handler-name)))
        cached-layers                (filter #(= :extended-cache (:layer/origin %)) layers)]
    (if (seq cached-layers)
      (instrumented-containerizer containerizer cached-layers)
      containerizer)))

(defn- execute-containerization
  "Takes a JibContainerBuilder object and a map representing the build plan and
  execute the containerization process."
  [^JibContainerBuilder container-builder ^IPersistentMap build-plan ^IPersistentMap options]
  (.containerize container-builder (make-containerizer build-plan options)))

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
  [{:layer/keys [id entries]}]
  (loop [^FileEntriesLayer$Builder layer (.. FileEntriesLayer builder (setName (name id)))
         entries                         entries]
    (if-not (seq entries)
      (.build layer)
      (let [layer-entry (first entries)]
        (.addEntry layer (make-file-entry layer-entry))
        (recur layer (rest entries))))))

(defn- ^ISeq make-file-entries-layers
  "Returns a sequence of FileEntriesLayer objects for those layers in
  the build plan whose :image.layer/origin is :file-entries."
  [{:image/keys [layers]}]
  (keep (fn [layer]
          (when (= :file-entries (:layer/origin layer))
            (make-file-entries-layer layer)))
        layers))

(defn-   ^RegistryImage make-registry-image
  "Given an ImageReference instance, returns a new registry image
  object."
  [^ImageReference image-reference]
  (let [^CredentialRetriever retriever (jib.credentials/retriever-chain image-reference)]
    (.. RegistryImage (named image-reference)
        (addCredentialRetriever retriever))))

(defn-   ^Boolean is-in-docker-hub?
  "Is the image in question stored in the official Docker hub?"
  [^ImageReference image-reference]
  (= "registry-1.docker.io"
     (.getRegistry image-reference)))

(defn-   ^JibContainerBuilder make-container-builder
  "Takes the base image reference and returns an instance of JibContainerBuilder
  to build an image that extends the base image in question."
  [^String from]
  (let [^ImageReference image-reference (ImageReference/parse from)]
    (.. Jib (from
             (if (is-in-docker-hub? image-reference)
               (str image-reference)
               (make-registry-image image-reference)))
        (setCreationTime (misc/now))
        (setFormat ImageFormat/Docker))))

(defn- ^String qualified-image-reference
  "Takes a JibContainer object and returns a fully qualified image reference in
  the form [registry]/<repository>@<digest> such as
  nubank/my-app@sha256:05edcc9e7f16871319590dccb2f9045f168a2cbdfab51b35b98693e57d42f7f7.

  If the container in question targets the official Docker registry, omits the
  registry portion."
  [^JibContainer container]
  (let [^ImageReference target-image (.getTargetImage container)
        ^String digest               (str (.getDigest container))
        ^String reference            (format "%s@%s"
                                             (.getRepository target-image) digest)]
    (if (is-in-docker-hub? target-image)
      reference
      (str (.getRegistry target-image) "/" reference))))

(defn- ^IPersistentMap layer-ids-and-digests
  "Returns a map of layer ids as keywords to their respective digests as
  strings."
  [^ISeq file-entries-layers ^File cache-dir]
  (let [^Cache cache (jib.cache/get-cache cache-dir)]
    (reduce (fn [result ^FileEntriesLayer layer]
              (assoc result (keyword (.getName layer))
                     (str (.. (jib.cache/get-cached-layer-by-file-entries-layer cache layer) getDigest))))
            {} file-entries-layers)))

(defn containerize
  "Takes a map representing a build plan and a map of additional options and produces a container."
  [^IPersistentMap build-plan ^IPersistentMap options]
  (let [{:image/keys [from layers]} build-plan
        {:keys [cache-dir]}         options
        ^ISeq file-entries-layers   (make-file-entries-layers build-plan)
        ^JibContainer container     (-> (make-container-builder from)
                                        (.setFileEntriesLayers file-entries-layers)
                                        (execute-containerization build-plan options))]
    (def c container)
    {:image/reference            (qualified-image-reference container)
     :image/digest               (str (.getDigest container))
     :application.layers/digests (layer-ids-and-digests file-entries-layers cache-dir)}))
