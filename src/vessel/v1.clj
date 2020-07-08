(ns vessel.v1
  "Specs for vessel v1 descriptor."
  (:require [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen])
  (:import java.io.File))

;; Manifest (e.g. vessel.edn).

(s/def ::from string?)

(s/def ::target string?)

(s/def ::app-root string?)

(s/def ::app-type #{:jar :war})

(s/def ::main-ns symbol?)

(s/def ::resource-paths (s/coll-of string? :kind set?))

(s/def ::source-paths (s/coll-of string? :kind set? :min-count 1))

(s/def :copy/from string?)

(s/def :copy/to string?)

(s/def :copy/preserve-permissions boolean?)

(s/def ::extra-paths (s/coll-of
                      (s/keys :req-un [:copy/from :copy/to]
                              :opt-un [:copy/preserve-permissions])
                      :min-count 1))

(s/def ::classifiers (s/map-of keyword? string? :min-count 1))

(s/def ::compiler-opts (s/map-of keyword? any?))

(s/def ::labels (s/map-of string? string?))

(s/def ::user string?)

(s/def ::manifest (s/keys
                   :req [::from ::target ::main-ns ::source-paths]
                   :opt [::app-root ::app-type ::resource-paths ::extra-paths  ::classifiers ::labels ::user]))

;; Classpath resolution.

(def file? (s/with-gen (partial instance? File)
             #(gen/return (io/file "deps.edn"))))

(s/def :clojure/tool #{:leiningen :tools.deps.alpha})

(s/def :project/descriptor file?)
