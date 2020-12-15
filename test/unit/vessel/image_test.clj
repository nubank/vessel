(ns vessel.image-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer :all]
            [matcher-combinators.test :refer [match?]]
            [mockfn.macros :refer [providing]]
            [vessel.image :as image]
            [vessel.misc :as misc])
  (:import java.io.File))

(deftest internal-dep?-test
  (let [m2          (io/file "/home/builder/.m2/repository")
        common-core (io/file m2 "common-core/common-core/1.0.0/common-core-1.0.0.jar")
        k8s-clj     (io/file m2 "nubank/kubernetes-clj/5.0.0/kubernetes-clj-5.0.0.jar")]
    (are [predicate file regexes] (predicate (image/internal-dep? file {:internal-deps-re regexes}))
      false? common-core nil
      false? common-core #{}
      true?  common-core #{#"common-.*\.jar$"}
      true?  common-core #{#"common-.*\.jar$" #"nubank"}
      true?  k8s-clj     #{#"common-.*\.jar$" #"nubank"})))

(deftest resource?-test
  (let [resources      (io/file "/home/builder/my-app/resources")
        more-resources (io/file "/home/builder/my-app/src/clj/resources")]
    (are [predicate file resource-paths] (predicate (image/resource? file {:resource-paths resource-paths}))
      false? (io/file resources "resource1.edn")      nil
      false? (io/file resources "resource1.edn")      #{}
      true?  (io/file resources "resource1.edn")      #{resources}
      false? (io/file more-resources "resource2.edn") #{resources}
      true?  (io/file more-resources "resource2.edn") #{resources more-resources})))

(deftest source-file?-test
  (let [sources      (io/file "/home/builder/my-app/src")
        more-sources (io/file "/home/builder/my-app/clj/src")]
    (are [predicate file source-paths] (predicate (image/source-file? file {:source-paths source-paths}))
      false? (io/file sources "main_app/server.clj")  nil
      false? (io/file sources "my_app/server.clj")    #{}
      true?  (io/file sources "my_app/server.clj")    #{sources}
      false? (io/file more-sources "my_app/misc.clj") #{sources}
      true?  (io/file more-sources "my_app/misc.clj") #{sources more-sources})))

(deftest render-image-spec-test
  (let [target-dir        (io/file "/home/builder/projects/my-app/.vessel")
        web-inf-dir       (io/file target-dir "WEB-INF")
        project-dir       (io/file "/home/builder/projects/my-app")
        m2-dir            (io/file "/home/builder/.m2/repository")
        app               #:app {:classes
                                 {(io/file web-inf-dir "classes/my_app/server.class")         (io/file project-dir "src")
                                  (io/file web-inf-dir "classes/config.edn")                  (io/file project-dir "resources")
                                  (io/file web-inf-dir "classes/my_company/core__init.class") (io/file m2-dir "my-company/my-company/1.0.0/my-company-1.0.0.jar")}
                                 :lib [(io/file web-inf-dir "lib/aws-java-sdk-1.11.602.jar")]}
        options           {:app-root         (io/file "/opt/app")
                           :internal-deps-re #{#"my-company.*\.jar$"}
                           :resource-paths   #{(io/file project-dir "resources")}
                           :source-paths     [(io/file project-dir "src")]
                           :manifest
                           {:base-image
                            {:image {:repository "jetty" :registry "my-company.com" :tag "v1"}}
                            :image {:repository "my-app" :registry "my-company.com" :tag "v2"}}
                           :tarball          (io/file "my-app.tar")
                           :user             "jetty"
                           :target-dir       target-dir}
        modification-time (misc/now)]
    (providing [(misc/last-modified-time (partial instance? File)) modification-time]

               (testing "takes an options map and returns a map representing an image spec
  for the files in question"
                 (is (= #:image{:from
                                #:image   {:registry "my-company.com" :repository "jetty" :tag "v1"}
                                :user     "jetty"
                                :name
                                #:image   {:registry "my-company.com" :repository "my-app" :tag "v2"}
                                :layers
                                [#:image.layer{:name  "external-deps"
                                               :entries
                                               [#:layer.entry{:source            "/home/builder/projects/my-app/.vessel/WEB-INF/lib/aws-java-sdk-1.11.602.jar"
                                                              :target            "/opt/app/WEB-INF/lib/aws-java-sdk-1.11.602.jar"
                                                              :modification-time modification-time}]
                                               :churn 1}
                                 #:image.layer{:name  "internal-deps"
                                               :entries
                                               [#:layer.entry{:source            "/home/builder/projects/my-app/.vessel/WEB-INF/classes/my_company/core__init.class"
                                                              :target            "/opt/app/WEB-INF/classes/my_company/core__init.class"
                                                              :modification-time modification-time}]
                                               :churn 3}
                                 #:image.layer{:name  "resources"
                                               :entries
                                               [#:layer.entry{:source            "/home/builder/projects/my-app/.vessel/WEB-INF/classes/config.edn"
                                                              :target            "/opt/app/WEB-INF/classes/config.edn"
                                                              :modification-time modification-time}]
                                               :churn 5}
                                 #:image.layer{:name  "sources"
                                               :entries
                                               [#:layer.entry{:source            "/home/builder/projects/my-app/.vessel/WEB-INF/classes/my_app/server.class"
                                                              :target            "/opt/app/WEB-INF/classes/my_app/server.class"
                                                              :modification-time modification-time}]
                                               :churn 7}]
                                :tar-path "my-app.tar"}
                        (image/render-image-spec app options))))

               (testing "accepts a set of extra-paths that will beget extra layers"
                 (is (= [#:image.layer{:name    "extra-files-1"
                                       :entries [#:layer.entry{:source            "/home/builder/logback.xml"
                                                               :target            "/opt/app/WEB-INF/classes/logback.xml"
                                                               :modification-time modification-time}
                                                 #:layer.entry{:source            "/home/builder/web.xml"
                                                               :target            "/opt/app/web.xml"
                                                               :modification-time modification-time}]
                                       :churn   0}
                         #:image.layer{:name  "external-deps"
                                       :entries
                                       [#:layer.entry{:source            "/home/builder/projects/my-app/.vessel/WEB-INF/lib/aws-java-sdk-1.11.602.jar"
                                                      :target            "/opt/app/WEB-INF/lib/aws-java-sdk-1.11.602.jar"
                                                      :modification-time modification-time}]
                                       :churn 1}
                         #:image.layer{:name  "internal-deps"
                                       :entries
                                       [#:layer.entry{:source            "/home/builder/projects/my-app/.vessel/WEB-INF/classes/my_company/core__init.class"
                                                      :target            "/opt/app/WEB-INF/classes/my_company/core__init.class"
                                                      :modification-time modification-time}]
                                       :churn 3}
                         #:image.layer{:name  "resources"
                                       :entries
                                       [#:layer.entry{:source            "/home/builder/projects/my-app/.vessel/WEB-INF/classes/config.edn"
                                                      :target            "/opt/app/WEB-INF/classes/config.edn"
                                                      :modification-time modification-time}]
                                       :churn 5}
                         #:image.layer{:name  "sources"
                                       :entries
                                       [#:layer.entry{:source            "/home/builder/projects/my-app/.vessel/WEB-INF/classes/my_app/server.class"
                                                      :target            "/opt/app/WEB-INF/classes/my_app/server.class"
                                                      :modification-time modification-time}]
                                       :churn 7}
                         #:image.layer{:name  "extra-files-2"
                                       :entries
                                       [#:layer.entry{:source            "/home/builder/context.xml"
                                                      :target            "/opt/app/context.xml"
                                                      :modification-time modification-time}]
                                       :churn 8}]
                        (:image/layers (image/render-image-spec app (assoc options :extra-paths
                                                                           #{{:source (io/file "/home/builder/logback.xml")
                                                                              :target (io/file "/opt/app/WEB-INF/classes/logback.xml")
                                                                              :churn  0}
                                                                             {:source (io/file "/home/builder/web.xml")
                                                                              :target (io/file "/opt/app/web.xml")
                                                                              :churn  0}
                                                                             {:source (io/file "/home/builder/context.xml")
                                                                              :target (io/file "/opt/app/context.xml")
                                                                              :churn  8}}))))))

               (testing "when the key :preserve-file-permissions? is set to true, assoc's
    the original posix file permissions of the files that will be copied to the
    container"
                 (let [file-permissions #{"OTHERS_READ"
                                          "OWNER_WRITE"
                                          "OWNER_READ"
                                          "GROUP_READ"}]
                   (providing [(misc/posix-file-permissions #(instance? File %)) file-permissions]
                              (is (match? #:image{:layers
                                                  [#:image.layer{:name    "extra-files-1"
                                                                 :entries [#:layer.entry{:source           "/home/builder/run-app.sh"
                                                                                         :target           "/usr/local/bin/run-app"
                                                                                         :file-permissions file-permissions}]
                                                                 :churn   0}
                                                   #:image.layer{:name  "external-deps"
                                                                 :entries
                                                                 [#:layer.entry{:source           "/home/builder/projects/my-app/.vessel/WEB-INF/lib/aws-java-sdk-1.11.602.jar"
                                                                                :target           "/opt/app/WEB-INF/lib/aws-java-sdk-1.11.602.jar"
                                                                                :file-permissions file-permissions}]
                                                                 :churn 1}
                                                   #:image.layer{:name  "internal-deps"
                                                                 :entries
                                                                 [#:layer.entry{:source           "/home/builder/projects/my-app/.vessel/WEB-INF/classes/my_company/core__init.class"
                                                                                :target           "/opt/app/WEB-INF/classes/my_company/core__init.class"
                                                                                :file-permissions file-permissions}]
                                                                 :churn 3}
                                                   #:image.layer{:name  "resources"
                                                                 :entries
                                                                 [#:layer.entry{:source           "/home/builder/projects/my-app/.vessel/WEB-INF/classes/config.edn"
                                                                                :target           "/opt/app/WEB-INF/classes/config.edn"
                                                                                :file-permissions file-permissions}]
                                                                 :churn 5}
                                                   #:image.layer{:name  "sources"
                                                                 :entries
                                                                 [#:layer.entry{:source           "/home/builder/projects/my-app/.vessel/WEB-INF/classes/my_app/server.class"
                                                                                :target           "/opt/app/WEB-INF/classes/my_app/server.class"
                                                                                :file-permissions file-permissions}]
                                                                 :churn 7}]}
                                          (image/render-image-spec app (assoc options :extra-paths
                                                                              #{{:source (io/file "/home/builder/run-app.sh")
                                                                                 :target (io/file "/usr/local/bin/run-app")
                                                                                 :churn  0}}
                                                                              :preserve-file-permissions? true))))))))))
