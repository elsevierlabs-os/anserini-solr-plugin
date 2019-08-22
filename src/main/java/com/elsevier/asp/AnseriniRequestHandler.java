package com.elsevier.asp;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TopDocs;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.handler.RequestHandlerBase;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.response.SolrQueryResponse;
import org.apache.solr.search.SolrIndexSearcher;

import io.anserini.rerank.RerankerCascade;
import io.anserini.rerank.RerankerContext;
import io.anserini.rerank.ScoredDocuments;
import io.anserini.rerank.lib.AxiomReranker;
import io.anserini.rerank.lib.IdentityReranker;
import io.anserini.rerank.lib.Rm3Reranker;
import io.anserini.rerank.lib.ScoreTiesAdjusterReranker;
import io.anserini.search.query.BagOfWordsQueryGenerator;
import io.anserini.search.query.SdmQueryGenerator;
import io.anserini.util.AnalyzerUtils;


public class AnseriniRequestHandler extends RequestHandlerBase {

	@Override
	public String getDescription() {
		return "Configurable Anserini like query handling with reranking";
	}

	@Override
	public void handleRequestBody(SolrQueryRequest req, SolrQueryResponse resp) throws Exception {
		
		// step 1: extract parameters from request
		String q = req.getParams().get("q");
		String textField = req.getParams().get("tf", "para_text");
		String similarity = req.getParams().get("sim", "bm");  // [bm, ql]
		String qtype = req.getParams().get("qtype", "bow");    // [bow, sdm]
		String rtype = req.getParams().get("rtype", "rm3");    // [rm3, ax, id]
		int rerankCutoff = Integer.valueOf(req.getParams().get("rerankCutoff", "50"));
		
		// step 2: analyze query
		String fieldName = textField + "_" + similarity;
		Analyzer analyzer = req.getSchema().getFieldType(fieldName).getQueryAnalyzer();
		
		// step 3: parse query and transform to query A
		Query query = null;
		if ("bow".equals(qtype)) { // BoW
			query = new BagOfWordsQueryGenerator().buildQuery(fieldName, analyzer, q);
		} else { // SDM
			float termWeight = Float.valueOf(req.getParams().get("sdm.termWeight", "0.85"));
			float orderedWindowWeight = Float.valueOf(req.getParams().get("sdm.orderedWindowWeight", "0.1"));
			float unorderedWindowWeight = Float.valueOf(req.getParams().get("sdm.unorderedWindowWeight", "0.05"));
			query = new SdmQueryGenerator(termWeight, orderedWindowWeight, unorderedWindowWeight)
					.buildQuery(fieldName, analyzer, q);
		}
		
		// step 4: execute query A and get results
		SolrIndexSearcher searcher = req.getSearcher();
		TopDocs topDocs = searcher.search(query, rerankCutoff);
		long numFound = topDocs.totalHits.value;
		ScoredDocuments baseResults = ScoredDocuments.fromTopDocs(topDocs, searcher);
		
//		List<String> results = new ArrayList<String>();
//		for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
//			Document doc = searcher.doc(scoreDoc.doc);
//			String docId = doc.get("doc_id");
//			String paraId = doc.get("para_id");
//			results.add(docId + "/" + paraId);
//		}
		
		// step 5: build reranker pipeline for query B and execute
		boolean restrictRerankedToBase = req.getParams().getBool("restrict", false); // to test ANSERINI-422
		Query queryFilter = null;
		if (restrictRerankedToBase) {
			queryFilter = buildQueryFilter(baseResults);
		}
		List<String> queryTokens = AnalyzerUtils.tokenize(analyzer, q);
		RerankerContext<Integer> rerankCtx = new RerankerContext<>(searcher, 
				0, query, null, q, queryTokens, queryFilter, null);
		RerankerCascade cascade = new RerankerCascade();
		if ("rm3".equals(rtype)) { // "rm3"
			int fbTerms = Integer.valueOf(req.getParams().get("rm3.fbTerms", "10"));
			int fbDocs = Integer.valueOf(req.getParams().get("rm3.fbDocs", "10"));
			float originalQueryWeight = Float.valueOf(req.getParams().get("rm3.originalQueryWeight", "0.5"));
			cascade.add(new Rm3Reranker(analyzer, fieldName, fbTerms, fbDocs, originalQueryWeight, true));
		} else if ("ax".equals(rtype)) { // "ax" -- axiomatic
			int R = Integer.valueOf(req.getParams().get("ax.R", "20"));
			int N = Integer.valueOf(req.getParams().get("ax.N", "20"));
			int K = Integer.valueOf(req.getParams().get("ax.K", "1000"));
			int M = Integer.valueOf(req.getParams().get("ax.M", "30"));
			float beta = Float.valueOf(req.getParams().get("ax.beta", "0.4"));
			cascade.add(new AxiomReranker<>(null, null, fieldName, true, (long) M, R, N, beta, K, 
					(String) null, false, false));
		} else { // "id"
			cascade.add(new IdentityReranker());
		}
		cascade.add(new ScoreTiesAdjusterReranker());
		ScoredDocuments rerankedResults = cascade.run(baseResults, rerankCtx);
		
//		RerankedResult rerankedResults = null;
//		RerankerFactory rf = new RerankerFactory();
//		Map<String,Float> params = new HashMap<String,Float>();
//		// :HACK: to allow testing ANSERINI-422
//		params.put("_restrict", req.getParams().getBool("_restrict", false) ? 1.0F: 0.0F);
//		if ("rm3".equals(rtype)) {       // "rm3"
//			params.put("fbTerms", Float.valueOf(req.getParams().get("rm3.fbTerms", "10")));
//			params.put("fbDocs", Float.valueOf(req.getParams().get("rm3.fbDocs", "10")));
//			params.put("originalQueryWeight", 
//					Float.valueOf(req.getParams().get("rm3.originalQueryWeight", "0.5")));
//			rerankedResults = rf.rerankWithRM3(topDocs.scoreDocs, params, q, fieldName, 
//					analyzer, searcher);
//		} else if ("ax".equals(rtype)) { // "ax"
//			params.put("R", Float.valueOf(req.getParams().get("ax.R", "20")));
//			params.put("N", Float.valueOf(req.getParams().get("ax.N", "20")));
//			params.put("K", Float.valueOf(req.getParams().get("ax.K", "1000")));
//			params.put("M", Float.valueOf(req.getParams().get("ax.M", "30")));
//			params.put("beta", Float.valueOf(req.getParams().get("ax.beta", "0.4")));
//			rerankedResults = rf.rerankWithAxiom(topDocs.scoreDocs, params, q, 
//					fieldName, analyzer, searcher);
//		} else {                         // "id"
//			rerankedResults = rf.rerankWithIdentity(topDocs.scoreDocs);
//		}

		// step 6: create additional header information
		NamedList<Object> header = resp.getResponseHeader();
		Map<String,String> requestParams = new HashMap<String,String>();
		Iterator<Entry<String,String[]>> it = req.getParams().iterator();
		while (it.hasNext()) {
			Entry<String,String[]> e = it.next();
			requestParams.put(e.getKey(), e.getValue()[0]);
		}
		header.add("params", requestParams);
		header.add("query_a", query);

		// TODO: need to expose the query_b from the RerankerCascade[0].reranker
		// TODO: need to find a better way of reporting errors from reranking 
		// (or other steps)
//		header.add("query_b", rerankedResults.getQuery());
//		if (rerankedResults.getErrorMessage() != null) {
//			header.add("error_message", rerankedResults.getErrorMessage());
//		}
		
		// step 7: create paginated SolrDocumentList for response
		int start = req.getParams().getInt("start", 0);
		int rows = req.getParams().getInt("rows", 10);
		String[] fieldList = req.getParams().get("fl").split(",");
		int currDoc = 0;
		SolrDocumentList doclist = new SolrDocumentList();
		for (Document idoc : rerankedResults.documents) {
			if (currDoc < start) {
				currDoc++;
				continue;
			}
			SolrDocument oDoc = new SolrDocument();
			for (String fieldElementName : fieldList) {
				if (! "para_text".equals(fieldElementName)) {
					oDoc.addField(fieldElementName, idoc.get(fieldElementName));
				} else {
					oDoc.addField("para_text", idoc.getFields(fieldName)[0]);
				}
			}
			doclist.add(oDoc);
			currDoc++;
			if (currDoc >= start + rows) break;
		}
		doclist.setNumFound(numFound);
		doclist.setStart(start);

		resp.add("docs", doclist);
		
//		for (ScoreDoc scoreDoc : rerankedResults.getDocuments()) {
//			if (currDoc < start) {
//				currDoc++;
//				continue;
//			}
//			Document idoc = searcher.doc(scoreDoc.doc);
//			SolrDocument oDoc = new SolrDocument();
//			for (String fieldElementName : fieldList) {
//				if (! "para_text".equals(fieldElementName)) {
//					oDoc.addField(fieldElementName, idoc.get(fieldElementName));
//				} else {
//					oDoc.addField("para_text", idoc.getFields(fieldName)[0]);
//				}
//			}
//			doclist.add(oDoc);
//			currDoc++;
//			if (currDoc >= start + rows) break;
//		}
//		doclist.setNumFound(numFound);
//		doclist.setStart(start);
//		resp.add("docs", doclist);
	}
	
	private Query buildQueryFilter(ScoredDocuments baseResults) {
		return null;
	}
}
