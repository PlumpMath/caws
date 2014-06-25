(ns caws.core
  (:require [nio.core :as nio]))

;; concept...
;; every view gets an in-port and an out-port
;; they can be socket ports, in which case you push bytes onto them
;; or they can be HTTP ports, in which case you push content onto them
;; you can also use symbols to push other stuff
;; like: (go (>! out-port {:headers {:content-type "text/html" :status 200}}))
;;       (go (>! out-port "<html></html">))
;;       (go (>! out-port :close))

(defn start [router & {:keys [ip port]
                       :or {ip "0.0.0.0" port 8080}}]
  )

(defn route [path in-port out-port]
  )

(defn routes [mappings]

  (loop [prefixes (keys mappings)]
    (let [prefix (first prefixes)]
      (if (.startsWith path prefix)
        (let [next (mappings prefix)]
          (if (instance? java.util.Map next)
            (invoke-view (.substring path (count prefix)) next)
            (next))))))
  )

(defn bing [in-port out-port]
  )

(start (route {"/foo" {"/bing" bing}}))
