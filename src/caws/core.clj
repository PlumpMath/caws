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

(defn state= [state channel]
  (= (bit-and (.readyOps channel) state) state))

(defn accept-connection [server-socket selector]
  (let [channel (-> server-socket (.accept) (.getChannel))]
    (println "Connection from" channel)
    (doto channel
      (.configureBlocking false)
      (.register selector SelectionKey/OP_READ))))


(defn route-request [metadata socket-channel router]
  (let [request (http/parse-request (:body metadata))]
    (router request (:in metadata) (:out metadata))
    (go-loop [token (<! (:out metadata))]
             (cond
              (= :end token)
              (.close (.socket socket-channel))

              (instance? String token)
              (.write socket-channel (util/string->buffer token))

              :else
              (http/write-response token socket-channel)
              )
             (if (not (= :end token))
               (recur (<! (:out metadata)))))))


(defn read-socket [selected-key rout]
  (let [socket-channel (.channel selected-key)]
    (.clear *buffer*)
    (.read socket-channel *buffer*)
    (.flip *buffer*)
    (if (= (.limit *buffer*) 0)
      (do
        (println "Lost connection from" socket-channel)
        (.cancel selected-key)
        (.close (.socket socket-channel)))
      (do
        (let [metadata (.attachment selected-key)
              body (metadata :body)]
          (.attach selected-key (assoc metadata :body (str body (util/buffer->string *buffer*))))
          (if (http/is-full-request? (:body metadata))
            (route-request metadata socket-channel rout))))
      )))


(defn react [selector server-socket rout]
  (while true
    (when (> (.select selector) 0)
      (let [selected-keys (.selectedKeys selector)]
        (doseq [key selected-keys]
          (condp state= key
            SelectionKey/OP_ACCEPT
            (do
              (.attach key {:body nil :in (chan) :out (chan)})
              (accept-connection server-socket selector))

            SelectionKey/OP_READ
            (read-socket key rout)))
        (.clear selected-keys)))))


(defn run [router & {:keys [ip port]
                     :or {ip "0.0.0.0" port 8080}}]
  (apply react (conj (setup port) router)))

(defn route [mappings]
  "TODO: add regex support, too, cuz we should."
  (let [route** (fn route* [mappings path request in-chan out-chan]
                  (loop [prefixes (sort-by (fn [x] (count (str x))) > (keys mappings))]
                    (if (empty? prefixes)
                      (go (>! out-chan 404)
                          (>! out-chan :end))
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
      (route** (mappings (:command request)) (:path request) request in-chan out-chan))))


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


(defn set-content-length [response]
  (assoc response
    :headers (assoc (:headers response) :content-length (count (:body response)))))


(defn ensure-finished [response]
  (set-content-length
   (if (and (not (nil? (:code response))) (not (nil? (:body response))))
     response
     (assoc response
       :code (or (:code response) :ok)
       :body (or (:body response) "")))))


(defmacro finish []
  `(do
     (>! ~'output (ensure-finished response))
     (>! ~'output :end)))


(defmacro handle-error [e]
  '(let [e# ~e]
     (.printStackTrace e#)
     (assoc response
       :code :error
       :headers {}
       :body (str e#))
     (finish)))

(defn request-body [] (:body *request*))
(defn request-headers [] (:headers *request*))
(defn request-method [] (:method *request*))

(defn GET [key] ((:get *request*) key))
(defn POST [key] ((:post *request*) key))

(defn ->param-getter [param-type]
  (assert (or (= :get param-type) (= :post param-type)))
  ({:get 'GET :post 'POST} param-type))

(defn ->lookup-pair [[param-type name]] `[~name (~(->param-getter param-type) ~(str name))])

(defn parse-view-params [params]
  (vec (concat [(first params) '*request*]
               (apply concat (map ->lookup-pair (partition 2 (rest params)))))))

(defmacro view [name params & body]
  `(defn ~name [~'--caws-request ~'--caws-in-chan ~'--caws-out-chan]
     (go
      (binding [~'*request* ~'--caws-request
                ~'*input* ~'--caws-in-chan
                ~'*output* ~'--caws-out-chan
                ~'*response* (http/empty-response)]
        (try
         (let ~(parse-view-params params) ;; [request ...]
           ~@body)
         (catch Exception e
           (handle-error e)))))))

;;(view x [req :get foo :post bing]


(defmacro read-token []
  `(<! ~'in-chan))

(defmacro get-path [] `~'path)

(defn remove-prefix [prefix str]
  (.substring str (count prefix)))

(defmacro static-view [name internal-base external-base]
  `(defn ~name [~'request ~'in-chan ~'out-chan]
     (go
      (let [~'*response-code (atom nil)]
        (try
         (send-headers {:content-type "text/javascript"})
         (send-body (slurp (io/file (io/resource (str ~internal-base (remove-prefix ~external-base (get-path)))))))
         (catch Exception e
           (write-error e)))))))

;; (static-view js "js" "/js")

;; (view home
;;       (send-headers {:content-type "text/html"})
;;       (send-body "This is the home page<script src='/js/test.js'></script>\n"))

;; (view bing
;;       (send-headers {:content-type "text"})
;;       (send-body "This is the Bing\n"))

;; (view bang
;;       (send-headers {:content-type "text"})
;;       (send-body "This is the Bang\n"))

;; (view slow
;;       (Thread/sleep 10000)
;;       (send-body "Done sleeping"))

;; (run (route {:get
;;              {"/js" js
;;               "/foo" {"/bing" bing
;;                       "/bang" bang}
;;               "/slow-thing" slow
;;               "/" home}}))
