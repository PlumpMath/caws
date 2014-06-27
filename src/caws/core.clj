(ns caws.core
  (:import (java.nio.channels Selector SelectionKey
                              ServerSocketChannel SocketChannel)
           (java.nio ByteBuffer CharBuffer)
           (java.nio.charset Charset)
           (java.net InetSocketAddress)
           (java.io IOException))
  (:require [clojure.core.async :as async :refer [go go-loop >! <! <!! >!! chan]]
            [clojure.string :as string :refer [join split]]))

;; concept...
;; every view gets an in-port and an out-port
;; they can be socket ports, in which case you push bytes onto them
;; or they can be HTTP ports, in which case you push content onto them
;; you can also use symbols to push other stuff
;; like: (go (>! out-port {:headers {:content-type "text/html" :status 200}}))
;;       (go (>! out-port "<html></html">))
;;       (go (>! out-port :close))

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

(defn buffer->string
  ([byte-buffer]
   (buffer->string byte-buffer (Charset/defaultCharset)))
  ([byte-buffer charset]
   (.toString (.decode charset byte-buffer))))

(defn string->buffer
  ([string]
   (string->buffer string (Charset/defaultCharset)))
  ([string charset]
   (.encode charset string)))

(defn accept-connection [server-socket selector]
  (let [channel (-> server-socket (.accept) (.getChannel))]
    (println "Connection from" channel)
    (doto channel
      (.configureBlocking false)
      (.register selector SelectionKey/OP_READ))))


(defn header-string-to-keyword [header-string]
  (keyword (.trim (.toLowerCase header-string))))

(defn parse-header [header]
  (let [bits (seq (.split header "\\s*:\\s*"))]
    [(header-string-to-keyword (first bits)) (second bits)])) ;; TODO: I want to use symbols for the header names...

(defn parse-headers [header-lines]
  "Returns a map of keyword -> string value"
  (if header-lines
    (apply array-map (apply concat (map parse-header header-lines)))
    {}))

(defn parse-request [request-string]
  "returns [method path headers body]"
  (let [parts (seq (.split request-string "\r?\n\r?\n"))
        head-lines (seq (.split (first parts) "\r?\n"))
        request-line (first head-lines)
        header-lines (rest head-lines)
        body (if (> (count parts) 1) (join "\n" (rest parts)) nil)]
    (let [_ (seq (.split request-line "\\s+"))
          command (.trim (first _))
          path (.trim (second _))]
      [command path (parse-headers header-lines) body])))

(defn is-full-request? [content]
  (re-find #"\r?\n\r?\n" content))

(defn write-headers [headers socket-channel]
  (doseq [pair (seq headers)]
    (let [k (first pair) v (second pair)]
      (.write socket-channel
              (string->buffer (str (.substring (str k) 1) ":" v "\n"))))))

(defn route-request [request-string socket-channel route]
  (apply

   (fn [command path headers body]
     (println command path headers body)
     (let [in-chan (chan 10) out-chan (chan)] ;; hm... should out-chan be unbuffered?
       (when headers
         (>!! in-chan :headers)
         (>!! in-chan headers))

       (when body
         (>!! in-chan :content)
         (>!! in-chan body))

       (route (keyword (.toLowerCase command)) path in-chan out-chan)
       (go-loop [token (<! out-chan)]
                (println "TOKEN" token)
                (cond
                 (instance? Long token)
                 (do (.write socket-channel (string->buffer (str "HTTP " token "\n")))
                     (recur (<! out-chan)))

                 (= :headers token)
                 (do (write-headers (<! out-chan) socket-channel)
                     (recur (<! out-chan)))

                 (= :body token)
                 (do (.write socket-channel (string->buffer "\n\n"))
                     (loop [part (<! out-chan)]
                       (if (= :end part)
                         (.close (.socket socket-channel))
                         (do (.write socket-channel (string->buffer part))
                             (recur (<! out-chan)))
                         )))))))

   (parse-request request-string)))

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
        (let [existing-content (.attachment selected-key)]
          (.attach selected-key (str existing-content (buffer->string *buffer*))))
        (if (is-full-request? (.attachment selected-key))
          (route-request (.attachment selected-key) socket-channel rout)))
      )))

(defn react [selector server-socket rout]
  (while true
    (when (> (.select selector) 0)
      (let [selected-keys (.selectedKeys selector)]
        (doseq [k selected-keys]
          (condp state= k
            SelectionKey/OP_ACCEPT
            (accept-connection server-socket selector)
            SelectionKey/OP_READ
            (read-socket k rout)))
        (.clear selected-keys)))))


(defn run [router & {:keys [ip port]
                     :or {ip "0.0.0.0" port 8080}}]
  (apply react (conj (setup port) router)))


;; what I want to do:
;;
;; read the full HTTP request in (asynchronously)
;; then put any content into the in-channel
;; then route based on the path
;; so GET /foo HTTP/1.1 will route a :get
;; (route :get path in-port out-port)
;;
;; to do this, I need to buffer all of the content until I've read all of the header
;; information, and then I'll immediately route, and take any content I have at that
;; point and dump it onto in-chan.
;; if/when I get more content, I dump that onto in-chan as well,
;; and I dump a :closed token onto in-chan when I get there.
;;
;;

(defn route [mappings]
  "TODO: add regex support, too, cuz we should."
  (let [route** (fn route* [mappings full-path path in-chan out-chan]
                  (loop [prefixes (keys mappings)]
                    (let [prefix (first prefixes)]
                      (println "prefix = " prefix)
                      (println "path is " path)
                      (if (.startsWith path prefix)
                        (let [next (mappings prefix)]
                          (if (instance? java.util.Map next)
                            (route* next full-path (.substring path (count prefix)) in-chan out-chan)
                            (do
                              (println "invoking" next)
                              (next path in-chan out-chan))
                            ))
                        (recur (rest prefixes))
                        )
                      )))]
    (fn [command path in-chan out-chan]
      (println "command = " command)
      (route** (mappings command) path path in-chan out-chan))))

(defn home [path in-port out-port]
  (println "HOME")
  (go (>! out-port 200)
      (>! out-port :headers)
      (>! out-port {:content-type "text"})
      (>! out-port :body)
      (>! out-port (str path " INDEX\n"))
      (>! out-port :end)))

(defn bing [path in-port out-port]
  (println "BING")
  (go (>! out-port 200)
      (>! out-port :headers)
      (>! out-port {:content-type "text"})
      (>! out-port :body)
      (>! out-port (str path " BING\n"))
      (>! out-port :end)))


(run (route {:get
             {"/foo" {"/bing" bing}
              "/" home}}))
