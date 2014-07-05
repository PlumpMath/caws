(ns caws.util
  (:import (java.nio.charset Charset)))


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

