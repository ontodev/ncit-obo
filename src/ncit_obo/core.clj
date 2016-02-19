(ns ncit-obo.core
  (:require [clojure.xml]
            [clojure.string :as string]
            [clj-yaml.core :as yaml])
  (:import (org.obolibrary.robot IOHelper)
           (org.semanticweb.owlapi.apibinding OWLManager)
           (org.semanticweb.owlapi.model AxiomType IRI OWLLiteral))
  (:gen-class))

;;;; Configuration

;;; We read a YAML file with several maps,
;;; and return a nested config map.

;;; - :prefixes map from prefix to URI
;;;   used to expand CURIEs to URIs
;;; - :mappings map from CURIE to CURIE
;;;   used to build :translations
;;; - :translations map from URI to URI
;;;   used to translate annotation properties
;;;   and XMLLiteral element names to annotation properties
;;; - :xml map of maps from the CURIE for a root element to:
;;;     - :primary the XML element to use for the main annotation property
;;;     - :secondary a list of other XML elements for annotating the annotation property

(defn expand
  "Given a map of prefixes and a string,
   try to expand string and return it,
   or just return it."
  [prefixes s]
  (let [[p l] (string/split s #":" 2)
        p (keyword p)]
    (if (find prefixes p)
      (str (get prefixes (keyword p)) l)
      s)))

(defn add-translation
  "Given a config map with :prefixes and :mappings maps,
   assoc a :translation map,
   and return the config map."
  [config]
  (assoc
   config
   :translations
   (->> config
        :mapping
        (map
         (fn [[k v]]
           [(expand (:prefixes config) (name k))
            (expand (:prefixes config) v)]))
        (into {}))))

(defn read-config
  "Given a configuration path, read the YAML
   and return a nested config map."
  [config-path]
  (->> config-path
       slurp
       yaml/parse-string
       add-translation))


(def ncit2obo
  {"http://ncicb.nci.nih.gov/xml/owl/EVS/Thesaurus.owl#P97"
   "http://purl.obolibrary.org/obo/IAO_0000115"
   :ncicp:def-source
   "http://purl.obolibrary.org/obo/IAO_0000119"
   :ncicp:term-source
   "http://purl.obolibrary.org/obo/IAO_0000117"})

(def io-helper (IOHelper.))
(def output-manager (. OWLManager createOWLOntologyManager))
(def data-factory (. output-manager getOWLDataFactory))

(defn get-property
  "Given the config map and an OWLAnnotationAssertionAxiom,
   if the property should be translated,
   then return the new property,
   otherwise return the axiom's same property."
  [config axiom]
  (let [property (.. axiom getProperty getIRI toString)]
    (if (find (:translations config) property)
      (. data-factory
         getOWLAnnotationProperty
         (IRI/create (get (:translations config) property)))
      (. axiom getProperty))))

; <ncicp:ComplexTerm xmlns:ncicp="http://ncicb.nci.nih.gov/xml/owl/EVS/ComplexProperties.xsd#">
;  <ncicp:term-name>Display Name</ncicp:term-name>
;  <ncicp:term-group>SY</ncicp:term-group>
;  <ncicp:term-source>NCI</ncicp:term-source>
; </ncicp:ComplexTerm>" ,

; <ncicp:ComplexDefinition xmlns:ncicp="http://ncicb.nci.nih.gov/xml/owl/EVS/ComplexProperties.xsd#">
;  <ncicp:def-definition>Provides an alternative Preferred Name for use in some NCI systems.</ncicp:def-definition>
;  <ncicp:def-source>NCI</ncicp:def-source>
; </ncicp:ComplexDefinition>" ,

; <ncicp:ComplexGOAnnotation xmlns:ncicp="http://ncicb.nci.nih.gov/xml/owl/EVS/ComplexProperties.xsd#">
;  <ncicp:go-term>ATP binding</ncicp:go-term>
;  <ncicp:go-id>GO:0005524</ncicp:go-id>
;  <ncicp:go-evi>TAS</ncicp:go-evi>
;  <ncicp:source-date>29-SEP-2003</ncicp:source-date>
;  <ncicp:go-source>CGAP</ncicp:go-source>
; </ncicp:ComplexGOAnnotation>" ,

(defn parse [s]
  "Given an XML string, return a nested map representation."
  (try
    (clojure.xml/parse
     (java.io.ByteArrayInputStream. (.getBytes s)))
    (catch Exception e nil)))

(defn get-primary-value
  "Given the primary element name and and some parsed XML,
   filter the XML content for the first matching tag,
   then return its content as an OWLLiteral string."
  [primary xml]
  (->> xml
       :content
       (filter #(= (name (:tag %)) primary))
       (map :content)
       first
       (apply str)
       (. data-factory getOWLLiteral)))

(defn get-secondary-annotation
  "Given the config map, a tag name, and a content string,
   try to find a translation of the tag
   return an OWLAnnotation."
  [config tag content]
  (if-let [property
           (->> tag
                (expand (:prefixes config))
                (get (:translations config)))]
    (. data-factory
       getOWLAnnotation
       (. data-factory
          getOWLAnnotationProperty
          (IRI/create property))
       (. data-factory
          getOWLLiteral
          content))
    (throw (Exception. (str "No translation for " tag)))))

(defn get-annotations
  "Given the config map and an OWLAnnotationAssertionAxiom,
   if the axiom value is an XMLLiteral then return a list
   with the primary value followed by zero or more annotations,
   otherwise return a list with just the axiom's value."
  [config axiom]
  (try
    (if (and (instance? OWLLiteral (. axiom getValue))
             (= (.. axiom getValue getDatatype getIRI toString)
                "http://www.w3.org/1999/02/22-rdf-syntax-ns#XMLLiteral"))
      (let [xml       (parse (.. axiom getValue getLiteral))
            root      (:tag xml)
            primary   (-> config :xml root :primary)
            secondary (-> config :xml root :secondary set)]
        ;(when-not primary
        ;  (throw (Exception. (str "Unknown XML tag " root))))
        (if (and xml root primary)
          (concat
           [(get-primary-value primary xml)]
           (->> (:content xml)
                (filter #(contains? secondary (name (:tag %))))
                (map (juxt #(name (:tag %))
                           #(apply str (:content %))))
                (map (partial apply get-secondary-annotation config))))
          [(. axiom getValue)]))
      [(. axiom getValue)])
    (catch Exception e 
      [(. axiom getValue)])))

(defn convert
  [config-path input-path output-path]
  (let [config          (read-config config-path)
        input-ontology  (. io-helper loadOntology input-path)
        output-ontology (. output-manager createOntology)]
    (doseq [axiom (. input-ontology getAxioms)]
      (if (= (. axiom getAxiomType) (. AxiomType ANNOTATION_ASSERTION))
        (let [subject  (. axiom getSubject)
              property (get-property config axiom)
              [value & annotations] (get-annotations config axiom)]
          (. output-manager
             addAxiom
             output-ontology
             (. data-factory
                getOWLAnnotationAssertionAxiom
                property
                subject
                value))
          (when (seq? annotations)
            (. output-manager
               addAxiom
               output-ontology
               (. data-factory
                  getOWLAnnotationAssertionAxiom
                  property
                  subject
                  value
                  (set annotations)))))
        (. output-manager addAxiom output-ontology axiom)))
    (. io-helper saveOntology output-ontology output-path)))

(defn -main
  [config-path input-path output-path]
  (println "Using" config-path "to convert" input-path "to" output-path)
  (convert config-path input-path output-path))
