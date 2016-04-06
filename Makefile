# NCIt ncit-obo Makefile
# James A. Overton
# 2016-02-11
#
# This file contains scripts for converting
# the NCI Thesaurus OWL representation
# to an OBO-compatible OWL representation.
#
# Requirements:
#
# - curl
# - unzip
# - Java 7
# - Maven
# - Leiningen 2.5+


### Configuration
#
# These are standard options to make Make sane:
# <http://clarkgrubb.com/makefile-style-guide#toc2>

MAKEFLAGS += --warn-undefined-variables
SHELL := bash
.SHELLFLAGS := -eu -o pipefail -c
.DEFAULT_GOAL := all
.DELETE_ON_ERROR:
.SUFFIXES:


APACHE_MIRROR := http://www.us.apache.org/dist
SPARQL_URL := http://localhost:3030/db
NCIT := http://ncicb.nci.nih.gov/xml/owl/EVS/Thesaurus.owl
OBO  := http://purl.obolibrary.org/obo


### Ontology Tools
#
# Download and use tools for working with ontologies.

lib:
	mkdir $@


#### Leiningen
#
# Make sure Leiningen is installed.

lib/lein: lib
	curl -Lko $@ https://raw.githubusercontent.com/technomancy/leiningen/stable/bin/lein
	chmod +x lib/lein


#### ROBOT
#
# download latest ROBOT jar
# load into local Maven repository

lib/robot.jar: | lib
	curl -Lko $@ https://build.berkeleybop.org/job/robot/lastSuccessfulBuild/artifact/bin/robot.jar

local_maven_repo: lib/robot.jar | lib/lein
	mkdir -p $@
	lib/lein deploy $@ org.obolibrary/robot 0.0.1-SNAPSHOT $<


#### Apache Jena
#
# Download and extract.

lib/jena.zip: | lib
	curl -Lo $@ $(APACHE_MIRROR)/jena/binaries/apache-jena-3.0.1.zip

lib/jena: lib/jena.zip
	rm -rf $@
	unzip -q -d lib $<
	mv lib/apache-jena-3.0.1 $@

lib/jena/bin/tdbloader: | lib/jena


#### Apache Jena Fuseki
#
# Download, extract, and configure.

lib/fuseki.zip: | lib
	curl -Lo $@ $(APACHE_MIRROR)/jena/binaries/apache-jena-fuseki-2.3.1.zip

lib/fuseki: lib/fuseki.zip
	rm -rf $@
	unzip -q -d lib $<
	mv lib/apache-jena-fuseki-2.3.1 $@

lib/fuseki/shiro.ini: | lib/fuseki
	echo '[urls]' > $@
	echo '# Everything open' >> $@
	echo '/**=anon' >> $@


### Build

target/uberjar/ncit-obo-0.1.0-SNAPSHOT-standalone.jar: project.clj src/ncit_obo/ | local_maven_repo lib/lein
	lib/lein uberjar


### NCI Thesaurus OWL
#
# TODO: use a link to the latest Thesaurus.owl
# TODO: use inferred file?

NCIT_RELEASE := 16.03d
NCIT_URL := http://evs.nci.nih.gov/ftp1/NCI_Thesaurus/archive/$(NCIT_RELEASE)_Release/Thesaurus_$(NCIT_RELEASE).OWL.zip

build:
	mkdir -p $@

build/Thesaurus.owl.zip: | build
	curl -L -o $@ $(NCIT_URL)

build/Thesaurus.owl: build/Thesaurus.owl.zip
	unzip -qc $< Thesaurus.owl > $@


### Gene Ontology

build/go.owl: | build
	curl -Lko $@ $(OBO)/go.owl


### Cell Ontology
#
# TODO: Get a standalone OWL file for CL.

build/cl.owl: | build
	curl -Lko $@ $(OBO)/cl.owl


### Configuration Sheets
#
# https://docs.google.com/spreadsheets/d/1PZ2lHSeRK_yn5LqYnejRyI-_GSXbkGKE5pjhTRqFjKI/edit
#
# WARN: The config.yml drives the configuration, not these sheets.

ID  := 1PZ2lHSeRK_yn5LqYnejRyI-_GSXbkGKE5pjhTRqFjKI
URL := https://docs.google.com/spreadsheets/d/$(ID)/export?format=tsv&gid=

build/AnnotationProperties.tsv: | build
	curl -L -o $@ "$(URL)1392969515"

build/XMLLiterals.tsv: | build
	curl -L -o $@ "$(URL)601798304"


### Local Triplestore
#
# Run Fuseki locally from a second shell:
#
#     make fuseki-load
#     make fuseki

.PHONY: fuseki-ncit
fuseki-ncit: build/ncit.owl | lib/jena/bin/tdbloader lib/fuseki
	$(word 1,$|) --loc $(word 2,$|)/tdb --graph '$(NCIT)' $<

# Handle OBO ontologies:

.PHONY: fuseki-%
fuseki-%: build/%.owl | lib/jena/bin/tdbloader lib/fuseki
	$(word 1,$|) --loc $(word 2,$|)/tdb --graph '$(OBO)/$*.owl' $<

.PHONY: fuseki-load
fuseki-load: fuseki-ncit fuseki-go

# Run Fuseki in a second shell!
.PHONY: fuseki
fuseki: | lib/fuseki/tdb lib/fuseki/shiro.ini
	cd lib/fuseki && export FUSEKI_HOME=. && ./fuseki-server --loc tdb /db


### Biological Processes
#
# First use Fuseki to fetch a list of children,
# then extract with ROBOT.
# This is just an example, not used by 'align' below.

build/biological_processes.tsv: src/biological_processes.rq | lib/fuseki/tdb build
	cat $< \
	| curl --fail --silent \
	-X POST -G '$(SPARQL_URL)/query' \
	-H 'Content-Type: application/sparql-query' \
	-H 'Accept: text/tab-separated-values' \
	-T - \
	| tail -n+2 \
	| sed 's/<//g' \
	| sed 's/>//g' \
	> $@

build/biological_processes.owl: build/Thesaurus.owl build/biological_processes.tsv | lib/robot.jar
	java -Xmx4G -jar $| extract \
	--input $(word 1,$^) \
	--upper-term '$(NCIT)#C17828' \
	--lower-terms $(word 2,$^) \
	--output $@


### Run

# Convert NCIt Thesaurus.owl to use OBO-style annotations.

build/ncit.owl: target/uberjar/ncit-obo-0.1.0-SNAPSHOT-standalone.jar config.yml build/Thesaurus.owl
	java -Xmx4g -jar $< convert $(word 2,$^) $(word 3,$^) $@

# Align GO_0044763 single-organism cellular process
# to ncit:C20480 Cellular Process
# and write a report.
#
# WARN: requires a runnig Fuseki instance, with NCIt and GO loaded.
# See above.

build/cellular_process.tsv: target/uberjar/ncit-obo-0.1.0-SNAPSHOT-standalone.jar build/Thesaurus.owl build/go.owl
	java -Xmx4g -jar $< align $(NCIT) ncit:C20480 $(OBO)/go.owl obo:GO_0044763 $@

# Compress build artifacts.

build/%.zip: build/%
	cd build && zip $*.zip $*


### Common Tasks

.PHONY: all
all: build/ncit.owl

.PHONY: clean
clean:
	rm -rf build lib local_maven_repo target

.PHONY: tidy
tidy:
	rm -f build/Thesaurus.owl*
