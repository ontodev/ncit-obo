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
              :synonym-type "exact"
              :normalized "foobar"}]
            [{:subject "bar"
              :predicate "oio:hasNarrowSynonym"
              :synonym "foo bar process"
              :synonym-type "other"
              :normalized "foobar"}])
           [["foo"
             "bar"
             "rdfs:label"
             "oio:hasNarrowSynonym"
             "Foo Bar"
             "foo bar process"
             "foobar"
             "exact-other"
             "50"]]))))
