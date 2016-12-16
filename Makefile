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
# - Java 8
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
NCIT_RELEASE := 16.03d
NCIT_DOWNLOAD := http://evs.nci.nih.gov/ftp1/NCI_Thesaurus/archive/$(NCIT_RELEASE)_Release/Thesaurus_$(NCIT_RELEASE).OWL.zip
OBO  := http://purl.obolibrary.org/obo
JAVA := java -Xmx4g
ROBOT := $(JAVA) -jar lib/robot.jar
NCIT_OBO_JAR := target/uberjar/ncit-obo-0.1.0-SNAPSHOT-standalone.jar
NCIT_OBO := $(JAVA) -jar $(NCIT_OBO_JAR)


### Ontology Tools
#
# Download and use tools for working with ontologies.

lib:
	mkdir $@


#### ROBOT
#
# download latest ROBOT jar
# load into local Maven repository

lib/robot.jar: | lib
	curl -Lko $@ https://build.berkeleybop.org/job/robot/lastSuccessfulBuild/artifact/bin/robot.jar

local_maven_repo: lib/robot.jar
	mkdir -p $@
	lein deploy $@ org.obolibrary/robot 0.0.1-SNAPSHOT $<


### Build

$(NCIT_OBO_JAR): project.clj src/ncit_obo/ | local_maven_repo
	lein uberjar


### NCI Thesaurus OWL
#
# TODO: use a link to the latest Thesaurus.owl
# TODO: use inferred file?

build:
	mkdir -p $@

build/Thesaurus.owl.zip: | build
	curl -L -o $@ $(NCIT_DOWNLOAD)

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


### Subsets
#
# Use ROBOT to extract various subsets of the converted ontology.

build/subsets:
	mkdir -p $@

build/subsets/biological_process.owl: build/ncit.owl | lib/robot.jar build/subsets
	$(ROBOT) extract \
	--input $< \
	--method MIREOT \
	--branch-from-term '$(NCIT)#C17828' \
	annotate \
	--ontology-iri '$(OBO)/ncit/subsets/biological_process.owl' \
	--output $@

build/subsets/neoplasm.owl: build/ncit.owl | lib/robot.jar build/subsets
	$(ROBOT) extract \
	--input $< \
	--method MIREOT \
	--branch-from-term '$(NCIT)#C3262' \
	annotate \
	--ontology-iri '$(OBO)/ncit/subsets/neoplasm.owl' \
	--output $@


### Run

# Convert NCIt Thesaurus.owl to use OBO-style annotations.

build/ncit.owl: $(NCIT_OBO_JAR) src/config.yml src/base.ttl build/Thesaurus.owl
	$(NCIT_OBO) convert $(wordlist 2,9,$^) $@


### Align
#
# We use ROBOT to run SPARQL queries and extract subclasses.

build/ncit_%.rq: src/subclass.rq
	sed 's/ROOT/ncit:$*/' < $< > $@

build/GO_%.rq: src/subclass.rq
	sed 's/ROOT/obo:GO_$*/' < $< > $@

build/ncit_%.csv: build/Thesaurus.owl build/ncit_%.rq | lib/robot.jar
	$(ROBOT) query --input $(word 1,$^) --select $(word 2,$^) $@

build/GO_%.csv: build/go.owl build/GO_%.rq | lib/robot.jar
	$(ROBOT) query --input $(word 1,$^) --select $(word 2,$^) $@

#### Align Cellular Process
#
# Align GO_0044763 single-organism cellular process
# to ncit:C20480 Cellular Process
# and write a report.

build/cellular_process.tsv: $(NCIT_OBO_JAR) build/ncit_C20480.csv build/GO_0044763.csv
	$(NCIT_OBO) align $(word 2,$^) $(word 3,$^) $@


# Compress build artifacts.

build/%.zip: build/%
	cd build && zip $*.zip $*

# Convert to OBO format.
# first to obtain valid OBO IDs, we have to satisfy the assumptions of the OWLAPI OBO writer that
# class IRIs are OBO purls (hack)

build/%-obouri.owl: build/%.owl
	perl -npe 's@http://ncicb.nci.nih.gov/xml/owl/EVS/Thesaurus.owl#@http://purl.obolibrary.org/obo/NCIT_@g' $< > $@.tmp && mv $@.tmp $@
build/%.obo: build/%-obouri.owl | lib/robot.jar
	$(ROBOT) convert --input $< --output $@


### Common Tasks

.PHONY: all
all: build/ncit.owl
all: build/ncit.obo
all: build/subsets/biological_process.owl
all: build/subsets/biological_process.obo
all: build/subsets/neoplasm.owl
all: build/subsets/neoplasm.obo

.PHONY: clean
clean:
	rm -rf build lib local_maven_repo target

.PHONY: tidy
tidy:
	rm -f build/Thesaurus.owl*
