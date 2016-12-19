(ns ncit-obo.align-test
  (:require [clojure.test :refer :all]
            [clojure.string :as string]
            [ncit-obo.align :refer :all]))

(deftest test-align-branches
  (testing "align"
    (is (= (align-branches
            [{:subject "foo"
              :predicate "rdfs:label"
              :synonym "Foo Bar"
              :normalized "foobar"}]
            [{:subject "bar"
              :predicate "rdfs:label"
              :synonym "foo bar process"
              :normalized "foobar"}])
           [["foo"
             "bar"
             "rdfs:label"
             "rdfs:label"
             "exact-exact"
             "Foo Bar"
             "foo bar process"
             "foobar"]]))))
