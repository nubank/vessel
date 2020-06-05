(ns vessel.v1
  "Specs for vessel v1 descriptor."
  (:require [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.test.check.generators :as gen])
  (:import java.io.File))

;; Manifest (e.g. vessel.edn).

(s/def ::source-paths (s/coll-of string? :kind set? :min-count 1))

(s/def ::resource-paths (s/coll-of string? :kind set?))

(s/def ::manifest (s/keys
                   :req [::source-paths]
                   :opt [::resource-paths]))

;; Classpath resolution.

(def file? (s/with-gen (partial instance? File)
             #(gen/return (io/file "deps.edn"))))

(s/def :clojure/tool #{:leiningen :tools.deps})

(s/def :project/file file?)
