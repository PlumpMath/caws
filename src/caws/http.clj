(ns caws.http
  (:require [clojure.string :as string :refer [join split trim]])
  (:require [caws.util :as util]))

(defrecord Request [method path headers get post body])
(defrecord Response [code headers body])

(defn make-response [code headers body]
  (Response. code headers body))

(defn symbol->code [s]
  (condp = s
    :ok 200
    :redirect-permanent 301
    :redirect 302
    :error 500
    :unavailable 503
    :bad-response 400
    :not-found 404))

(defn code->string [c]
  (condp = c
    200 "OK"
    301 "Moved Permanently"
    302 "Moved"
    400 "Bad Response"
    404 "Not Found"
    500 "Internal Error"
    503 "Service Unavailable"))

(defn empty-response [] (Response. nil {} nil))

(defn header-string-to-keyword [header-string]
  (keyword (.trim (.toLowerCase header-string))))

(defn parse-header [header]
  (let [bits (seq (split header #"\s*:\s*"))]
    [(header-string-to-keyword (first bits)) (second bits)])) ;; TODO: I want to use symbols for the header names...

(defn parse-headers [header-lines]
  "Returns a map of keyword -> string value"
  (if header-lines
    (apply array-map (apply concat (map parse-header header-lines)))
    {}))

(defn url-unescape [s] s) ;; TODO... actually this.

(defn parse-params [param-string]
  (apply array-map
         (apply concat
                (map (fn [match] [(url-unescape (nth match 1)) (url-unescape (nth match 2))])
                     (re-seq #"([^=&]*)=([^&]*)" param-string)))))

(defn parse-querystring [path]
  (let [q-mark (.indexOf path "?")]
    (if (= -1 q-mark) {} (parse-params (subs path (inc q-mark))))))

(defn trim-querystring [path]
  (let [q-mark (.indexOf path "?")]
    (if (= -1 q-mark)
      path
      (subs path 0 q-mark))))

(defn parse-post-body [headers body]
  (if (= "application/x-www-form-urlencoded" (headers :content-type))
    (parse-params body)
    {}))

(defn parse-request [request-string]
  "returns [method path headers body]"
  (let [parts (seq (split request-string #"\r?\n\r?\n"))
        head-lines (seq (split (first parts) #"\r?\n"))
        request-line (first head-lines)
        _ (seq (split request-line #"\s+"))
        command (keyword (.toLowerCase (trim (first _))))
        raw-path (.trim (second _))
        get (parse-querystring raw-path)
        path (trim-querystring raw-path)

        header-lines (rest head-lines)
        headers (parse-headers header-lines)

        body (if (> (count parts) 1) (join "\n" (rest parts)) nil)
        post (parse-post-body headers body)]
    (Request. command path headers get post body)))

(parse-request "GET /foo?a=b")

(defn write-headers [headers socket-channel]
  (doseq [pair (seq headers)]
    (let [k (first pair) v (second pair)]
      (.write socket-channel
              (util/string->buffer (str (subs (str k) 1) ":" v "\n"))))))

(defn write-response-code [code socket-channel]
  (let [numeric (symbol->code code)]
    (.write socket-channel (util/string->buffer (str "HTTP " numeric " " (code->string numeric) "\n")))))

(defn write-response [request response socket-channel]
  (println "RS" (:method request) (:path request) (:code response) (:headers response))
  (write-response-code (:code response) socket-channel)
  (write-headers (:headers response) socket-channel)
  (.write socket-channel (util/string->buffer "\n\n"))
  (.write socket-channel (util/string->buffer (:body response))))

(defn is-full-request? [content]
  (re-find #"\r?\n\r?\n" content))


