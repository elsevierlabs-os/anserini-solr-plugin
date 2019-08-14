# anserini-solr-plugin

Solr Plugin that supports [Anserini](https://github.com/castorini/anserini) style query expansion and reranking against Solr indexes.

### Description

Supports following similarity implementations for paragraph text.

* **Query Likelihood (QL)** -- via built-in DirichletLM Similarity
* **BM25** -- via built in BM25 Similarity (default)

Supports following query rewriting functionality (query A).

* **Bag of Words (BoW)** -- constructs OR query out of individual terms
* **Sequential Dependency Model (SDM)** -- constructs query out of individual terms, bigrams (ordered and unordered).

Supports following query reranking functionality (query B). Constructs more complex query based on results returned from Query A and applies it to the top ${rerankCutoff} results from Query A.

* **Relevance Model 3 (RM3)** -- extracts feature vectors from query and results from query A and top feature vectors from top terms from top documents of the result, and interpolates them to create new reranking query.
* **Axiomatic Reranker** -- computes mutual information between query terms and terms in top ${rerankedCutoff} documents, plus random documents not from top results, and scored. Uses top K terms to create new reranking query.
* **Identity Reranker** -- a do-nothing reranker, passes the results from query A unchanged. Useful for debugging.

### Building

Steps to build the JAR file from the code and deploy to Solr are as follows:

```bash
$ mvn clean package
$ mkdir -p ${SOLR_HOME}/server/solr/lib
$ cp target/anserini-solr-plugins-1.0-SNAPSHOT.jar ${SOLR_HOME}/server/solr/lib/
```

### Configuration

The plugin expects additional field types `text_bm` and `text_ql` to be defined in managed-schema.xml of the `${SOLR_HOME}/server/solr/${INDEX_NAME}/conf/managed-schema`. These can be found in [solr/schema-additions.xml](solr/schema-additions.xml). This is needed to support the QL and BM25 similarities defined above.

The plugin requires two fields `para_text_bm` and `para_text_ql` with field types `text_bm` and `text_ql` as defined in the previous step. There are no other specific field requirements. An example schema can be found in [solr/update-schema.sh](solr/update-schema.sh).

Please restart Solr after these steps so its class loader can pick up the new JAR file you provided it in the Building section.

The plugin is defined (in `${SOLR_HOME}/server/solr/${INDEX_NAME}/conf/solrconfig.xml`) as detailed in [solr/update-plugin.sh](solr/update-plugin.sh).

### Running

Plugin can be run using HTTP GET requests. A typical URL would be something like the following.

```
http://localhost:8983/solr/my_index_name/anserini?q=what+are+nails+made+of
```

Main parameters to tweak behavior are listed below.

* q -- question, URL encoded. Mandatory parameter.
* sim -- ql (Query Likelihood) or bm (BM25), default bm.
* qtyoe -- Query Expansion type. Valid values are bow (Bag of Words) or sdm (Sequential Dependency Model), default is bow.
* rtype -- Reranking type. Valid values are ax (Axiomatic), rm3 (Relevance Model 3), and id (Identity), default is rm3.
* start and rows -- for pagination, defaults to 0 and 10 respectively.

For certain qtype and rtype, there are some additional parameters that are listed in [solr/update-plugin.sh](solr/update-plugin.sh) with prefixes "sdm.", "ax.", and "rm3."

### Dependencies

Currently the only dependency is Solr, since we have copy-pasted relevant parts of Anserini functionality in the interests of time. Plan is to make Anserini a dependency and leverage its functionality directly.

* Solr 8.1.1

### Citing

If you need to cite this software in your own work, please use the following DOI.

Pal, Sujit (2015), Elsevier Labs. Anserini Solr Plugin [Computer Software]; [https://github.com/elsevierlabs-os/anserini-solr-plugin](https://github.com/elsevierlabs-os/anserini-solr-plugin).


[![DOI](https://zenodo.org/badge/202418267.svg)](https://zenodo.org/badge/latestdoi/202418267)

