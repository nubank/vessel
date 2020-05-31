(ns vessel.scratch
  (:require [vessel.program :as prog]
            [vessel.misc :as misc]))

(def opts (get-in prog/vessel [:commands "containerize" :opts]))

(defn- select-required-options
  "Returns a sequence of maps describing required options."
  [opts]
  (keep  (fn [opt]
           (let [opt-spec (apply hash-map opt)]
             (when (:required? opt-spec)
               {:id (:id opt-spec)
                :long-opt (second opt)})))
         opts))

(select-required-options opts)
