(ns ncit-obo.convert-test
  (:require [clojure.test :refer :all]
            [clojure.string :as string]
            [ncit-obo.convert :refer :all])
  (:import (org.obolibrary.robot DiffOperation)))

(deftest test-convert
  (testing "convert"
    (convert
     "src/config.yml"
     "src/base.ttl"
     "test/Thesaurus_sample.owl"
     "target/ncit_test.owl")
    (is
     (DiffOperation/equals
      (. io-helper loadOntology "test/ncit_sample.owl")
      (. io-helper loadOntology "target/ncit_test.owl")))))
