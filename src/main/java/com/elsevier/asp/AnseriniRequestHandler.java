package com.elsevier.asp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.handler.RequestHandlerBase;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.response.SolrQueryResponse;
import org.apache.solr.search.SolrIndexSearcher;


public class AnseriniRequestHandler extends RequestHandlerBase {

	@Override
	public String getDescription() {
		return "Configurable Anserini like query handling with reranking";
	}

	@Override
	public void handleRequestBody(SolrQueryRequest req, SolrQueryResponse resp) throws Exception {
		
		// step 1: extract parameters from request
		String q = req.getParams().get("q");
		String[] fq = req.getParams().getParams("fq");
		String similarity = req.getParams().get("sim", "bm");  // [bm, ql]
		String qtype = req.getParams().get("qtype", "bow");    // [bow, sdm]
		String rtype = req.getParams().get("rtype", "rm3");    // [rm3, ax, id]
		int rerankCutoff = Integer.valueOf(req.getParams().get("rerankCutoff", "50"));
		
		// step 2: analyze query
		String fieldName = "para_text_" + similarity;
		Analyzer analyzer = req.getSchema().getFieldType(fieldName).getQueryAnalyzer();
		
		// step 3: parse query and transform to query A
		QueryBuilderFactory qbf = new QueryBuilderFactory();
		Query query = null;
		if ("bow".equals(qtype)) { // "bow"
			query = qbf.buildBagOfWordsQuery(q, fq, fieldName, analyzer);
		} else {                   // "sdm"
			Map<String,Float> params = new HashMap<String,Float>();
			params.put("termWeight", 
					Float.valueOf(req.getParams().get("sdm.termWeight", "0.85")));
			params.put("orderedWindowWeight", 
					Float.valueOf(req.getParams().get("sdm.orderedWindowWeight", "0.1")));
			params.put("unorderedWindowWeight",
					Float.valueOf(req.getParams().get("sdm.unorderedWindowWeight", "0.05")));
			query = qbf.buildSeqDepModelQuery(q, fq, fieldName, analyzer, params);
		}
		
		// step 4: analyze results of query A and build query B
		SolrIndexSearcher searcher = req.getSearcher();
		TopDocs topDocs = searcher.search(query, rerankCutoff);
		long numFound = topDocs.totalHits.value;
		List<String> results = new ArrayList<String>();
		for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
			Document doc = searcher.doc(scoreDoc.doc);
			String docId = doc.get("doc_id");
			String paraId = doc.get("para_id");
			results.add(docId + "/" + paraId);
		}
		
		// step 5: run query B and return response
		RerankedResult rerankedResults = null;
		RerankerFactory rf = new RerankerFactory();
		Map<String,Float> params = new HashMap<String,Float>();
		// :HACK: to allow testing ANSERINI-422
		params.put("_restrict", req.getParams().getBool("_restrict", false) ? 1.0F: 0.0F);
		if ("rm3".equals(rtype)) {       // "rm3"
			params.put("fbTerms", Float.valueOf(req.getParams().get("rm3.fbTerms", "10")));
			params.put("fbDocs", Float.valueOf(req.getParams().get("rm3.fbDocs", "10")));
			params.put("originalQueryWeight", 
					Float.valueOf(req.getParams().get("rm3.originalQueryWeight", "0.5")));
			rerankedResults = rf.rerankWithRM3(topDocs.scoreDocs, params, q, fieldName, 
					analyzer, searcher);
		} else if ("ax".equals(rtype)) { // "ax"
			params.put("R", Float.valueOf(req.getParams().get("ax.R", "20")));
			params.put("N", Float.valueOf(req.getParams().get("ax.N", "20")));
			params.put("K", Float.valueOf(req.getParams().get("ax.K", "1000")));
			params.put("M", Float.valueOf(req.getParams().get("ax.M", "30")));
			params.put("beta", Float.valueOf(req.getParams().get("ax.beta", "0.4")));
			rerankedResults = rf.rerankWithAxiom(topDocs.scoreDocs, params, q, 
					fieldName, analyzer, searcher);
		} else {                         // "id"
			rerankedResults = rf.rerankWithIdentity(topDocs.scoreDocs);
		}

		// step 6: create additional header information
		NamedList<Object> header = resp.getResponseHeader();
		Map<String,String> requestParams = new HashMap<String,String>();
		Iterator<Entry<String,String[]>> it = req.getParams().iterator();
		while (it.hasNext()) {
			Entry<String,String[]> e = it.next();
			requestParams.put(e.getKey(), e.getValue()[0]);
		}
		header.add("params", requestParams);
		header.add("query_a", query.toString());
		if (! rtype.equals("id")) {
			header.add("query_b", "N/A");
		} else {
			header.add("query_b", rerankedResults.getQuery().toString());
		}
		if (rerankedResults.getErrorMessage() != null) {
			header.add("error_message", rerankedResults.getErrorMessage());
		}
		
		// step 7: create paginated SolrDocumentList for response
		int start = req.getParams().getInt("start", 0);
		int rows = req.getParams().getInt("rows", 10);
		String[] fieldList = req.getParams().get("fl").split(",");
		int currDoc = 0;
		SolrDocumentList doclist = new SolrDocumentList();
		for (ScoreDoc scoreDoc : rerankedResults.getDocuments()) {
			if (currDoc < start) {
				currDoc++;
				continue;
			}
			Document idoc = searcher.doc(scoreDoc.doc);
			SolrDocument oDoc = new SolrDocument();
			for (String fieldElementName : fieldList) {
				if (! "para_text".equals(fieldElementName)) {
					oDoc.addField(fieldElementName, idoc.get(fieldElementName));
				} else {
					oDoc.addField("para_text", idoc.getFields(fieldName)[0]);
				}
			}
			oDoc.addField("score", scoreDoc.score);
			doclist.add(oDoc);
			currDoc++;
			if (currDoc >= start + rows) break;
		}
		doclist.setNumFound(numFound);
		doclist.setStart(start);
		resp.add("docs", doclist);
	}
}
