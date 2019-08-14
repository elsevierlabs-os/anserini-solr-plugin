#!/bin/bash
curl -X POST -H 'Content-type:application/json' http://localhost:8983/solr/qaindex/config -d '{
  "add-requesthandler": {
    "name": "/anserini",
    "class": "com.elsevier.asp.AnseriniRequestHandler",
    "defaults": {
        "sim"                       : "bm",
        "qtype"                     : "bow",
        "rtype"                     : "rm3",
        "rerankCutoff"              : "50",
        "sdm.termWeight"            : "0.85",
        "sdm.orderedWindowWeight"   : "0.1",
        "sdm.unorderedWindowWeight" : "0.05",
        "rm3.fbTerms"               : "10",
        "rm3.fbDocs"                : "10",
        "rm3.originalQueryWeight"   : "0.5",
        "ax.R"                      : "20",
        "ax.N"                      : "20",
        "ax.K"                      : "1000",
        "ax.M"                      : "30",
        "ax.beta"                   : "0.4",
        "start"                     : "0",
        "rows"                      : "10",
        "fl"                        : "pii,isbns_f,book_title,chapter_title,para_id,para_text"
    }
  }
}'
