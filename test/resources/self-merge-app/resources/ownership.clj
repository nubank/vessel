(ns ownership)

;; A namespace that lives under resources/ (like opsbot's
;; squad_st_ownership.clj), required from src/. Its presence is what makes
;; `resources/` a namespace source discovered via `:classpath-files`, in
;; addition to being listed explicitly via `:resource-paths`.
(def squad-map
  {[:cx-data :general-queues] "S0803N1LAH0"})
