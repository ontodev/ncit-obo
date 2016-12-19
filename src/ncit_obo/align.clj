(ns ncit-obo.align
  (:require [clojure.string :as string]
            [clojure.java.io :as io]
            [clojure.data.csv :as csv]
            [clojure.math.combinatorics :as combo]
            [clojure-stemmer.porter.stemmer :refer [stemming]]))

(set! *warn-on-reflection* true)

(def stop-words
  #{"of" "a" "an" "the" "process"})

(defn normalize
  "Given an input string, return a normalized string."
  [input]
  (->> (-> input
           string/lower-case
           (string/split #"\W+"))
       (remove (partial contains? stop-words))
       (map stemming)
       sort
       (string/join "")))

(defn shorten
  "Given a string with IRIs,
   return a string with CURIEs."
  [s]
  (-> s
      (string/replace "http://www.w3.org/2000/01/rdf-schema#" "rdfs:")
      (string/replace "http://ncicb.nci.nih.gov/xml/owl/EVS/Thesaurus.owl#" "ncit:")
      (string/replace "http://www.geneontology.org/formats/oboInOwl#" "oio:")
      (string/replace "http://purl.obolibrary.org/obo/GO_" "GO:")))

;; A "SynonymMap" represents a synonym for a term.

(defn synonym-type
  [p]
  (if (contains?
       #{"http://www.w3.org/2000/01/rdf-schema#label"
         "http://www.geneontology.org/formats/oboInOwl#hasExactSynonym"}
       p)
    "exact"
    "other"))

(defn score
  [type1 type2]
  (case [type1 type2]
    ["exact" "exact"] 100
    ["exact" "other"] 50
    ["other" "exact"] 50
    ["other" "other"] 25))

(defn process-annotations
  "Given a path a CSV file with the results of a SPARQL query (subclass.rq)
   and return a list of SynonymMaps."
  [path]
  (with-open [reader (io/reader path)]
    (->> reader
         csv/read-csv
         rest
         (map
          (fn [[s p o]]
            {:subject      s
             :predicate    p
             :synonym      o
             :synonym-type (synonym-type p)
             :normalized   (normalize o)}))
         doall)))

(def headers
  ["Term 1"
   "Term 2"
   "Predicate 1"
   "Predicate 2"
   "Synonym 1"
   "Synonym 2"
   "Normalized Match"
   "Match Type"
   "Match Score"])

(defn format-row
  "Given two matched SynonymMaps
   return a vector for a row of the output table."
  [syn1 syn2]
  [(:subject syn1)
   (:subject syn2)
   (:predicate syn1)
   (:predicate syn2)
   (:synonym syn1)
   (:synonym syn2)
   (:normalized syn1)
   (str (:synonym-type syn1) "-" (:synonym-type syn2))
   (str (score (:synonym-type syn1) (:synonym-type syn2)))])

(defn align-branches
  "Given two sequences of SynonymMaps,
   return a sequence of alignment result rows."
  [branch1 branch2]
  (->> (combo/cartesian-product branch1 branch2)
       (filter (fn [[a b]] (= (:normalized a) (:normalized b))))
       (map (partial apply format-row))))

(defn align
  "Given two paths to results of a SPARQL query (subclass.rq)
   and an output file path,
   match the first branch against the second branch,
   and write a report to the output file."
  [input-path-1 input-path-2 output-path]
  (let [branch1 (process-annotations input-path-1)
        branch2 (process-annotations input-path-2)]
    (->> (align-branches branch1 branch2)
         (concat [headers])
         (map (partial string/join "\t"))
         (map shorten)
         (string/join "\n")
         (spit output-path))))
