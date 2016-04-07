# ncit-obo

This tool converts the NCI Thesaurus OWL file to new OWL file using OBO-standard annotations. The following converted files are available:

- Full translation (~500MB):
  [ncit.owl](https://build.berkeleybop.org/job/ncit-obo/lastSuccessfulBuild/artifact/build/ncit.owl)
- 'Biological Process' (C17828) branch (~4MB):
  [biological_process.owl](https://build.berkeleybop.org/job/ncit-obo/lastSuccessfulBuild/artifact/build/subsets/biological_process.owl)
- 'Neoplasm' (C3262) branch (~40MB):
  [neoplasm.owl](https://build.berkeleybop.org/job/ncit-obo/lastSuccessfulBuild/artifact/build/subsets/neoplasm.owl)


## Requirements

- GNU Make
- Java 7
- Maven 3
- Leiningen 2.5


## Usage

Run the `Makefile` to generate `build/ncit-obo.owl`:

    make clean all
