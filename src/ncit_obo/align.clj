(ns ncit-obo.align
  (:require [clojure.string :as string]
            [clojure.java.io :as io]
            [clj-http.client :as http]
            [clucy.core :as clucy])
  (:import (java.io StringReader)
           (org.apache.lucene.util AttributeFactory)
           (org.apache.lucene.analysis.standard StandardTokenizer)
           (org.apache.lucene.analysis.core StopFilter)
           (org.apache.lucene.analysis.util CharArraySet)
           (org.apache.lucene.analysis.tokenattributes CharTermAttribute)
           (org.apache.lucene.analysis.en PorterStemFilter EnglishAnalyzer)))

(def factory (AttributeFactory/DEFAULT_ATTRIBUTE_FACTORY))

(def stop-words
  #{"process"})

(defn get-tokenizer
  "Given an input string,
   return a configured tokenizer."
  [input]
  (-> (doto (StandardTokenizer. factory)
        (.setReader (StringReader. input))
        (.reset))
      (StopFilter. (EnglishAnalyzer/getDefaultStopSet))
      (StopFilter. (CharArraySet. stop-words true))
      (PorterStemFilter.)))

;; Loop over the tokens,
;; equivalent to while (tokenizer.incrementToken()) {},
;; collect strings (using CharTermAttribute),
;; and return a sorted, concatentated string.

(defn process-label
  "Given an input string,
   return a single, processed, concatenated string for comparison."
  [input]
  (let [tokenizer (get-tokenizer (string/lower-case input))
        attr      (.addAttribute tokenizer CharTermAttribute)]
    (->> (loop [result []]
           (if (.incrementToken tokenizer)
             (recur (conj result (.toString attr)))
             result))
         sort
         (string/join " "))))

;; Define common prefixes.
;; TODO: better to load from a shared file

(def prefixes "PREFIX rdfs:  <http://www.w3.org/2000/01/rdf-schema#>
PREFIX xsd:   <http://www.w3.org/2001/XMLSchema#>
PREFIX owl:   <http://www.w3.org/2002/07/owl#>
PREFIX obo:   <http://purl.obolibrary.org/obo/>
PREFIX ncit:  <http://ncicb.nci.nih.gov/xml/owl/EVS/Thesaurus.owl#>
PREFIX ncicp: <http://ncicb.nci.nih.gov/xml/owl/EVS/ComplexProperties.xsd#>
")

;; Query for IRI and labels in a given graph
;; for all terms that are subClassOf (transitive)
;; of a given term.

(def subclass-query
  (str prefixes "
SELECT *
FROM <%s>
WHERE {
  ?s rdfs:subClassOf* %s ;
     rdfs:label ?label .
}"))

(defn process-labels
  [graph term]
  (let [query  (format subclass-query graph term)
        params {:content-type "application/sparql-query"
                :accept "application/sparql-results+json"
                :as :json
                :body query}]
    (->> (http/post "http://localhost:3030/db/query" params)
         :body
         :results
         :bindings
         (map
          (fn [x]
            {:iri   (-> x :s :value)
             :label (-> x :label :value)
             :token (-> x :label :value process-label)})))))

;; Compare cell differentiation branches

(defn align-branches
  [graph1 root1 graph2 root2]
  (let [terms1 (process-labels graph1 root1)
        terms2 (process-labels graph2 root2)
        index (clucy/memory-index)]
    (apply clucy/add index terms1)
    (for [term terms2]
      (let [label   (-> term :token)
            results (clucy/search index label 1)]
        [(:iri term)
         (-> results first :iri)
         (:_max-score (meta results))
         (:label term)
         (-> results first :label)]))))

(defn align
  [graph1 root1 graph2 root2 output-path]
  (with-open [w (io/writer output-path)]
    (.write w (string/join "\t" ["ID1" "ID2" "Score" "Label1" "Label2"]))
    (.write w "\n")
    (doseq [alignment (align-branches graph1 root1 graph2 root2)]
      (.write
       w
       (-> (string/join "\t" alignment)
           (string/replace
            "http://ncicb.nci.nih.gov/xml/owl/EVS/Thesaurus.owl#"
            "ncit:")
           (string/replace
            "http://purl.obolibrary.org/obo/"
            "obo:")))
      (.write w "\n"))))

#_(align
   "http://purl.obolibrary.org/obo/go.owl"
   "obo:GO_0044763" ; single-organism cellular process
   "http://ncicb.nci.nih.gov/xml/owl/EVS/Thesaurus.owl"
   "ncit:C20480" ; Cellular Process
   )

#_(write-report
   "test.tsv"
   "http://purl.obolibrary.org/obo/go.owl"
 ;"obo:GO_0030154" ; cell differentiation
   "obo:GO_0044763" ; single-organism cellular process
   "http://ncicb.nci.nih.gov/xml/owl/EVS/Thesaurus.owl"
 ;"ncit:C16399" ; Cell Differentiation Process
   "ncit:C20480" ; Cellular Process
   )
