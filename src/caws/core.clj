(ns caws.core
  (:import (java.nio.channels Selector SelectionKey
                              ServerSocketChannel SocketChannel)
           (java.nio ByteBuffer CharBuffer)
           (java.nio.charset Charset)
           (java.net InetSocketAddress)
           (java.io IOException))
  (:require [caws.http :as http]
            [caws.util :as util])
  (:require [clojure.core.async :as async :refer [go go-loop >! <! <!! >!! chan]]
            [clojure.java.io :as io]))

(def *buffer* (ByteBuffer/allocate 16384)) ;; 16k...

(defn selector [server-socket-channel]
  (let [selector (Selector/open)]
    (.register server-socket-channel selector SelectionKey/OP_ACCEPT)
    selector))

(defn setup [port]
  (let [server-socket-channel (ServerSocketChannel/open)
        _ (.configureBlocking server-socket-channel false)
        server-socket (.socket server-socket-channel)
        inet-socket-address (InetSocketAddress. port)]
    (.bind server-socket inet-socket-address)
    [(selector server-socket-channel)
     server-socket]))

(defn state= [state ready-ops]
  (= (bit-and ready-ops state) state))

(defn accept-connection [server-socket selector]
  (let [channel (-> server-socket (.accept) (.getChannel))]
    (println "Connection from" channel)
    (doto channel
      (.configureBlocking false)
      (.register selector SelectionKey/OP_READ))))


(defn route-request [metadata socket-channel router]
  (let [request (http/parse-request (:body metadata))]
    (println "RQ" (:method request) (:path request) (:headers request))
    (router request (:in metadata) (:out metadata))
    (go-loop [token (<! (:out metadata))]
             (cond
              (= :end token)
              (.close (.socket socket-channel))

              (instance? String token)
              (.write socket-channel (util/string->buffer token))

              :else
              (http/write-response request token socket-channel))
             (if (not (= :end token))
               (recur (<! (:out metadata)))))))


(defn read-socket [selected-key rout]
  (let [socket-channel (.channel selected-key)]
    (try
     (.clear *buffer*)
     (.read socket-channel *buffer*)
     (.flip *buffer*)
     (if (= (.limit *buffer*) 0)
       (do
         (println "Lost connection from" socket-channel)
         (.cancel selected-key)
         (.close (.socket socket-channel)))
       (do
         (let [_metadata (.attachment selected-key)
               _body (_metadata :body)
               body (str _body (util/buffer->string *buffer*))
               metadata (assoc _metadata :body body)]
           (.attach selected-key metadata)
           (if (http/is-full-request? body)
             (route-request metadata socket-channel rout)))))
     (catch Exception e
       (.printStackTrace e)))))


(defn react [selector server-socket router]
  (while true
    (when (> (.select selector) 0)
      (let [selected-keys (.selectedKeys selector)]
        (doseq [key selected-keys]
          (when (.isValid key)
            (condp state= (.readyOps key)
              SelectionKey/OP_ACCEPT
              (accept-connection server-socket selector)

              SelectionKey/OP_READ
              (do
                (if (nil? (.attachment key))
                  (.attach key {:body nil :in (chan) :out (chan)}))
                (read-socket key router)))))
        (.clear selected-keys)))))


(defn run [router & {:keys [ip port]
                     :or {ip "0.0.0.0" port 8080}}]
  (apply react (conj (setup port) router)))

(def ^:dynamic *response* nil)
(def ^:dynamic *request* nil)
(def ^:dynamic *input* nil)
(def ^:dynamic *output* nil)

(defn set-response-code! [code]
  (set! *response* (assoc *response* :code code)))

(defn set-header! [k v]
  (set! *response* (assoc *response* :headers (assoc (:headers *response*) k v))))

(defn set-headers! [m]
  (set! *response* (assoc *response* :headers {})))

(defn set-body! [s]
  (set! *response* (assoc *response* :body s)))

(defn ensure-finished [response]
  (if (and (not (nil? (:code response))) (not (nil? (:body response))))
    response
    (assoc response
      :code (or (:code response) :ok)
      :body (or (:body response) ""))))

(defmacro finish []
  `(do
     (>! *output* (ensure-finished *response*))
     (>! *output* :end)))

(defmacro handle-error [e]
  `(let [e# ~e]
     (.printStackTrace e#)
     (binding [*response* (http/make-response :error {} (str e#))]
       (finish))))

(defmacro handle-missing [path]
  `(let [path# ~path]
     (println (str path# " Not Found"))
     (binding [*response* (http/make-response :not-found {} (str path# " Not Found"))]
       (finish))))

(defn route [mappings]
  "TODO: add regex support, too, cuz we should."
  (let [route** (fn route* [mappings path request in-chan out-chan]
                  (loop [prefixes (sort-by (fn [x] (count (str x))) > (keys mappings))]
                    (if (empty? prefixes)
                      (go
                       (binding [*output* out-chan]
                         (handle-missing (:path request))))

                      (let [prefix (first prefixes)]
                        (if (.startsWith path prefix)
                          (let [next (mappings prefix)]
                            (if (instance? java.util.Map next)
                              (route* next (.substring path (count prefix)) request in-chan out-chan)
                              (next request in-chan out-chan))
                            )
                          (recur (rest prefixes))
                          )
                        ))))]
    (fn [request in-chan out-chan]
      (route** (mappings (:method request)) (:path request) request in-chan out-chan))))




(defn request-body [] (:body *request*))
(defn request-headers [] (:headers *request*))
(defn request-method [] (:method *request*))

(defn GET [key] ((:get *request*) key))
(defn POST [key] ((:post *request*) key))

(defn ->param-getter [param-type]
  (assert (or (= :get param-type) (= :post param-type)) param-type)
  ({:get GET :post POST} param-type))

(defn ->lookup-pair [[param-type name]] `[~name (~(->param-getter param-type) ~(str name))])

(defn remove-prefix [prefix str]
  (.substring str (count prefix)))

(defn parse-view-params [params]
  (vec (concat [(first params) '*request*]
               (apply concat (map ->lookup-pair (partition 2 (rest params)))))))

(defmacro view [name params & body]
  (assert (vector? params) "(view <name> [<params])")
  `(defn ~name [~'--caws-request ~'--caws-in-chan ~'--caws-out-chan]
     (assert ~'--caws-out-chan)
     (go
      (binding [*request* ~'--caws-request
                *input* ~'--caws-in-chan
                *output* ~'--caws-out-chan
                *response* (http/empty-response)]
        (try
         (let ~(parse-view-params params) ;; [request ...]
           ~@body)
         (catch Exception e
           (handle-error e)))))))

(defmacro static-view [name internal-base external-base]
  `(defn ~name [~'--caws-request ~'--caws-in-chan ~'--caws-out-chan]
     (go
      (binding [*request* ~'--caws-request
                *input* ~'--caws-in-chan
                *output* ~'--caws-out-chan
                *response* (http/empty-response)]
        (try
         (set-headers! {:content-type "text/javascript"})
         (set-body! (slurp (io/file (io/resource (str ~internal-base (remove-prefix ~external-base (:path *request*)))))))
         (finish)
         (catch Exception e
           (handle-error e)))))))

;; (static-view js "js" "/js")

;; (view home [request]
;;       (set-headers! {:content-type "text/html"})
;;       (set-body! "<html>This is the home page<script src='/js/test.js'></script></html>\n")
;;       (finish))

;; (view bing [request]
;;       (set-headers! {:content-type "text"})
;;       (set-body! "This is the Bing\n")
;;       (finish))

;; (view bang [request :get token]
;;       (set-headers! {:content-type "text"})
;;       (set-body! (str "This is the Bang, and I got " token " \n"))
;;       (finish))

;; (view slow [request]
;;       (Thread/sleep 10000)
;;       (set-body! "Done sleeping")
;;       (finish))

;; (run (route {:get
;;              {"/js" js
;;               "/foo" {"/bing" bing
;;                       "/bang" bang}
;;               "/slow-thing" slow
;;               "/" home}}))
