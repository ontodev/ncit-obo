(defproject ncit-obo "0.1.0-SNAPSHOT"
  :description "Convert NCI Thesaurus OWL to OBO-standard OWL"
  :url "https://github.com/ontodev/ncit-obo"
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [org.clojure/math.combinatorics  "0.1.1"]
                 [clj-yaml "0.4.0"]
                 [clj-http "2.1.0"]
                 [cheshire "5.5.0"]
                 [clj-fuzzy "0.3.1"]
                 [clojure-stemmer "0.1.0"]
                 [clucy "0.4.0"]
                 [net.mikera/core.matrix "0.29.1"]
                 [org.obolibrary/robot "0.0.1-SNAPSHOT"]]
  :repositories [["local_maven_repo"
                  {:url "file:local_maven_repo"
                   :username ""
                   :password ""}]]
  :main ^:skip-aot ncit-obo.core
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all}})
