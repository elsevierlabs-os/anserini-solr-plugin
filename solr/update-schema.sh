#!/bin/bash
curl -X POST -H 'Content-type:application/json' http://localhost:8983/solr/qaindex/schema -d '{
  "add-field": {
    "name": "pii",
    "type": "string",
    "stored": true,
    "indexed": true
  },
  "add-field": {
    "name": "isbns_f",
    "type": "string",
    "stored": true,
    "indexed": false,
    "multiValued": true
  },
  "add-field": {
    "name": "isbns_u",
    "type": "string",
    "stored": true,
    "indexed": true,
    "multiValued": true
  },
  "add-field": {
    "name": "book_title",
    "type": "text_general",
    "stored": true,
    "indexed": true
  },
  "add-field": {
    "name": "chapter_title",
    "type": "text_general",
    "stored": true,
    "indexed": true
  },
  "add-field": {
    "name": "para_id",
    "type": "string",
    "stored": true,
    "indexed": true
  },
  "add-field": {
    "name": "para_text_bm",
    "type": "text_bm",
    "stored": true,
    "indexed": true,
    "termVectors": true,
    "termPositions": true,
    "termOffsets": true
  },
  "add-field": {
    "name": "para_text_ql",
    "type": "text_ql",
    "stored": true,
    "indexed": true,
    "termVectors": true,
    "termPositions": true,
    "termOffsets": true
  }
}'
