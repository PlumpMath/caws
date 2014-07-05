(ns caws.http-test
  (:require [clojure.test :refer :all]
            [caws.http :refer :all]))

(deftest headers
  (testing "header-string-to-keyword"
           (is (= :content-type (header-string-to-keyword "Content-Type")))
           (is (= :content-type (header-string-to-keyword " Content-Type ")))
           )

  (testing "parse-header"
           (is = ({:content-type "text/html"} (parse-header "Content-Type: text/html")))
           (is = ({:content-length "3"} (parse-header " Content-Length : 3 ")))
           )

  )

(deftest parsing
  (testing "parse-params"
           (is (= {"a" "hi" "b" "world"} (parse-params "a=hi&b=world")))
           (is (= {} (parse-post-body {} "a=hi&b=world")))
           (is (= {"a" "hi" "b" "world"} (parse-post-body {:content-type "application/x-www-form-urlencoded"} "a=hi&b=world")))
           )
  (testing "querystrings"
           (is (= {"z" "4"} (parse-querystring "foo?z=4"))))
  )
