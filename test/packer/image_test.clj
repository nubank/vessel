(ns packer.image-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer :all]
            [packer.image :as image]))

(def dir (io/file "projects/my-app/.packer"))

(deftest internal-dep?-test
  (are [predicate file regexes] (predicate (image/internal-dep? file {:internal-deps-re regexes}))
    false? (io/file dir "common_core") nil
    false? (io/file dir "common_core") #{}
    true? (io/file dir "common_core") #{#"common_.*$"}
    true? (io/file dir "common_core") #{#"common_.*$" #"nubank$"}
    true? (io/file dir "nubank") #{#"common_.*$" #"nubank$"}))

(deftest resource?-test
  (are [predicate file known-resources] (predicate (image/resource? file {:known-resources known-resources}))
    false? (io/file dir "resource1.edn") nil
    false? (io/file dir "resource1.edn") []
    true? (io/file dir "resource1.edn") [(io/file "my-app/resources/resource1.edn")]
    true? (io/file dir "resource2.edn") [(io/file "my-app/resources/resource1.edn") (io/file "my-app/resources/resource2.edn")]))

(deftest source-file?-test
  (are [predicate file known-sources] (predicate (image/source-file? file {:known-sources known-sources}))
    false? (io/file dir "my_app") nil
    false? (io/file dir "my_app") []
    true? (io/file dir "my_app") [(io/file "my-app/src/my_app")]
    true? (io/file dir "other") [(io/file "my-app/src/my_app") (io/file "my-app/src/other")]))

(deftest render-containerization-plan-test
  (testing "takes an options map and returns a map representing a containerization
plan for the files in question"
    (let [options
          {:app-root (io/file "/opt/app")
           :files [(io/file dir "my_company")
                   (io/file dir "my_app")
                   (io/file dir "clojure")
                   (io/file dir "config.edn")]
           :internal-deps-re #{#"my_company.*$"}
           :known-resources [(io/file "config.edn")]
           :known-sources [(io/file "my_app")]
           :manifest
           {:base-image {:name "jetty" :registry "my-company.com" :version "v1"}
            :image {:name "my-app" :registry "my-company.com" :version "v2"}}
           :tarball (io/file "my-app.tar")}]
      (is (= #:image{:from
                     #:image{:registry "my-company.com" :repository "jetty" :tag "v1"}
                     :name
                     #:image{:registry "my-company.com" :repository "my-app" :tag "v2"}
                     :layers
                     [#:image.layer{:name :external-deps
                                    :source ["projects/my-app/.packer/clojure"]
                                    :target "/opt/app/WEB-INF/classes"}
                      #:image.layer{:name :internal-deps
                                    :source ["projects/my-app/.packer/my_company"]
                                    :target "/opt/app/WEB-INF/classes"}
                      #:image.layer{:name :resources
                                    :source ["projects/my-app/.packer/config.edn"]
                                    :target "/opt/app/WEB-INF/classes"}
                      #:image.layer{:name :source-files
                                    :source ["projects/my-app/.packer/my_app"]
                                    :target "/opt/app/WEB-INF/classes"}]
                     :tar-path "my-app.tar"}
             (image/render-containerization-plan options))))))
