(ns caws.core)

(defn foo
  "I don't do a whole lot."
  [x]
  (println x "Hello, World!"))

(defn start [router & {:keys [ip port]
                       :or {ip "0.0.0.0" port 8080}}]
  )
