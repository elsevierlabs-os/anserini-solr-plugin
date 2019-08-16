package com.elsevier.asp;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.Set;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.BoostQuery;
import org.apache.lucene.search.DocValuesFieldExistsQuery;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.util.BytesRef;
import org.apache.solr.search.SolrIndexSearcher;

public class RerankerFactory {

	public RerankedResult rerankWithRM3(ScoreDoc[] inputs, Map<String, Float> params, String queryString,
	    String fieldName, Analyzer analyzer, SolrIndexSearcher searcher) {

		int fbDocs = params.get("fbDocs").intValue();
		int fbTerms = params.get("fbTerms").intValue();
		float originalQueryWeight = params.get("originalQueryWeight");

		// construct feature vectors for query and results from Query A
		List<String> terms = AnalyzerUtils.tokenizeQuery(queryString, fieldName, analyzer);
		FeatureVector queryVector = FeatureVector.fromTerms(terms).scaleToUnitL1Norm();
		DirectoryReader reader = searcher.getIndexReader();
		FeatureVector documentVector = aggregateDocumentVectors(inputs, reader, fbDocs, fbTerms, fieldName);

		// interpolate query and document vectors
		FeatureVector interpolatedVector = FeatureVector.interpolate(queryVector, documentVector, originalQueryWeight);

		// construct new query
		BooleanQuery.Builder queryBuilder = new BooleanQuery.Builder();
		Iterator<String> it = interpolatedVector.iterator();
		while (it.hasNext()) {
			String term = it.next();
			float prob = interpolatedVector.getFeatureWeight(term);
			queryBuilder.add(new BoostQuery(new TermQuery(new Term(fieldName, term)), prob), BooleanClause.Occur.SHOULD);
		}

		// restrict query B to results returned by query A
		BooleanQuery.Builder rerankQueryBuilder = new BooleanQuery.Builder();
		rerankQueryBuilder.add(queryBuilder.build(), BooleanClause.Occur.MUST);
		BooleanQuery originalResultsFilter = buildResultFilter(inputs, reader);
		rerankQueryBuilder.add(originalResultsFilter, BooleanClause.Occur.FILTER);

		boolean doRestrict = (params.get("_restrict").intValue() == 1);

		// retrieve reranked results from Query B
		try {
			TopDocs topDocs = null;
			if (doRestrict) {
				topDocs = searcher.search(rerankQueryBuilder.build(), inputs.length);	
			} else {
				topDocs = searcher.search(queryBuilder.build(), inputs.length);
			}
			return new RerankedResult(queryBuilder.build(), topDocs.scoreDocs);
		} catch (IOException e) {
			e.printStackTrace();
			return new RerankedResult(queryBuilder.build(), inputs, e.getMessage());
		}
	}

	public RerankedResult rerankWithAxiom(ScoreDoc[] inputs, Map<String, Float> params, String queryString,
	    String fieldName, Analyzer analyzer, SolrIndexSearcher searcher) {

		int R = params.get("R").intValue(); // number of top docs from inputs
		int N = params.get("N").intValue(); // + (N-1)*R random documents from index
		int K = params.get("K").intValue(); // top similar terms to consider
		int M = params.get("M").intValue(); // number of expansion terms
		float beta = params.get("beta"); // scaling factor

		BooleanQuery.Builder queryBuilder = new BooleanQuery.Builder();
		try {

			// get the docIds to use (R top docs + (N-1)*R random docs)
			DirectoryReader reader = searcher.getIndexReader();

			// extract inverted list from reranking pool
			Set<Integer> usedDocs = collectDocIds(inputs, R, N, searcher, reader);
			Map<String, Set<Integer>> termInvertedList = extractTerms(usedDocs, fieldName, searcher, reader);

			// calculate all terms in reranking pool and pick top K
			Map<String, Double> termScores = computeTermScores(termInvertedList, queryString, fieldName, analyzer, M, K, beta,
			    searcher, reader);

			// build query B
			if (termScores.isEmpty()) {
				return new RerankedResult(queryBuilder.build(), inputs);
			}
			for (Map.Entry<String, Double> termScore : termScores.entrySet()) {
				String term = termScore.getKey();
				float prob = termScore.getValue().floatValue();
				queryBuilder.add(new BoostQuery(new TermQuery(new Term(fieldName, term)), prob), BooleanClause.Occur.SHOULD);
			}

			// restrict query B to results returned by query A
			BooleanQuery.Builder rerankQueryBuilder = new BooleanQuery.Builder();
			rerankQueryBuilder.add(queryBuilder.build(), BooleanClause.Occur.MUST);
			BooleanQuery originalResultsFilter = buildResultFilter(inputs, reader);
			rerankQueryBuilder.add(originalResultsFilter, BooleanClause.Occur.FILTER);

			TopDocs topDocs = searcher.search(queryBuilder.build(), inputs.length);
			return new RerankedResult(queryBuilder.build(), topDocs.scoreDocs);

		} catch (Exception e) {
			e.printStackTrace();
			return new RerankedResult(queryBuilder.build(), inputs, e.getMessage());
		}
	}

	public RerankedResult rerankWithIdentity(ScoreDoc[] inputs) {
		// returns inputs without reranking -- for debugging use mostly
		return new RerankedResult(null, inputs);
	}

	private FeatureVector aggregateDocumentVectors(ScoreDoc[] docs, DirectoryReader reader, int fbDocs, int fbTerms,
	    String fieldName) {
		FeatureVector f = new FeatureVector();
		int numDocs = (docs.length < fbDocs) ? docs.length : fbDocs;
		Set<String> vocab = new HashSet<String>();
		FeatureVector[] docVectors = new FeatureVector[numDocs];
		for (int i = 0; i < numDocs; i++) {
			try {
				Terms terms = reader.getTermVector(docs[i].doc, fieldName);
				FeatureVector docVector = createDocumentVector(terms, fieldName, reader);
				docVector.pruneToSize(fbTerms);
				vocab.addAll(docVector.getFeatures());
				docVectors[i] = docVector;
			} catch (IOException e) {
				e.printStackTrace();
				// return empty feature vector
				return f;
			}
		}
		// precompute norms once and cache results
		float[] norms = new float[docVectors.length];
		for (int i = 0; i < docVectors.length; i++) {
			norms[i] = (float) docVectors[i].computeL1Norm();
		}
		for (String term : vocab) {
			float fbWeight = 0.0f;
			for (int i = 0; i < docVectors.length; i++) {
				if (norms[i] > 0.001f) {
					fbWeight += (docVectors[i].getFeatureWeight(term) / norms[i]) * docs[i].score;
				}
			}
			f.addFeatureWeight(term, fbWeight);
		}
		f.pruneToSize(fbTerms);
		f.scaleToUnitL1Norm();
		return f;
	}

	private FeatureVector createDocumentVector(Terms terms, String fieldName, DirectoryReader reader) {
		FeatureVector f = new FeatureVector();
		try {
			int numDocs = reader.numDocs();
			TermsEnum termsEnum = terms.iterator();
			BytesRef text;
			while ((text = termsEnum.next()) != null) {
				String term = text.utf8ToString();
				// remove very short and very long terms, and those with non-ascii chars
				if (term.length() < 2 || term.length() > 20)
					continue;
				if (!term.matches("[a-z0-9]+"))
					continue;
				// remove terms that are very common (df > 0.1)
				int df = reader.docFreq(new Term(fieldName, term));
				float ratio = (float) df / numDocs;
				if (ratio > 0.1f)
					continue;
				// add frequency
				int freq = (int) termsEnum.totalTermFreq();
				f.addFeatureWeight(term, (float) freq);
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
		return f;
	}

	private BooleanQuery buildResultFilter(ScoreDoc[] inputs, DirectoryReader reader) {
		// use original query as filter since we want to rerank from what we got
		BooleanQuery.Builder filterBuilder = new BooleanQuery.Builder();
		for (ScoreDoc input : inputs) {
			try {
				String id = reader.document(input.doc).get("id");
				filterBuilder.add(new TermQuery(new Term("id", id)), BooleanClause.Occur.SHOULD);
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		return filterBuilder.build();
	}

	private Set<Integer> collectDocIds(ScoreDoc[] inputs, int R, int N, SolrIndexSearcher searcher,
	    DirectoryReader reader) throws IOException {
		// select R*M documents from original ranking list
		Set<Integer> docIdSet = new HashSet<Integer>();
		for (int i = 0; i < Math.min(R, inputs.length); i++) {
			docIdSet.add(inputs[i].doc);
		}
		// + (N-1)*R random documents from index
		ScoreDoc[] allDocs = searcher.search(new DocValuesFieldExistsQuery("id"), reader.maxDoc()).scoreDocs;
		int targetSize = R * N;
		Random random = new Random();
		while (docIdSet.size() < targetSize) {
			docIdSet.add(allDocs[random.nextInt(allDocs.length)].doc);
		}
		return docIdSet;
	}

	private Map<String, Set<Integer>> extractTerms(Set<Integer> docIds, String fieldName, SolrIndexSearcher searcher,
	    DirectoryReader reader) throws Exception {
		Map<String, Set<Integer>> termDocidSets = new HashMap<String, Set<Integer>>();
		for (int docId : docIds) {
			Terms terms = reader.getTermVector(docId, fieldName);
			if (terms == null) {
				continue;
			}
			TermsEnum te = terms.iterator();
			if (te == null) {
				continue;
			}
			while ((te.next()) != null) {
				String term = te.term().utf8ToString();
				// We do some noisy filtering here ... pure empirical heuristic
				if (term.length() < 2)
					continue;
				if (!term.matches("[a-z]+"))
					continue;
				if (!termDocidSets.containsKey(term)) {
					termDocidSets.put(term, new HashSet<Integer>());
				}
				termDocidSets.get(term).add(docId);
			}
		}
		return termDocidSets;
	}

	private Map<String, Double> computeTermScores(Map<String, Set<Integer>> termInvertedList, String queryString,
	    String fieldName, Analyzer analyzer, int M, int K, float beta, SolrIndexSearcher searcher, DirectoryReader reader)
	    throws IOException {

		class ScoreComparator implements Comparator<Pair<String, Double>> {
			public int compare(Pair<String, Double> a, Pair<String, Double> b) {
				int cmp = Double.compare(b.getRight(), a.getRight());
				if (cmp == 0) {
					return a.getLeft().compareToIgnoreCase(b.getLeft());
				} else {
					return cmp;
				}
			}
		}

		// get collection statistics so that we can get idf later on.
		final long docCount = reader.numDocs() == -1 ? reader.maxDoc() : reader.numDocs();

		// calculate the Mutual Information between term with each query term
		List<String> queryTerms = AnalyzerUtils.tokenizeQuery(queryString, fieldName, analyzer);
		Map<String, Integer> queryTermsCounts = new HashMap<String, Integer>();
		for (String qt : queryTerms) {
			queryTermsCounts.put(qt, queryTermsCounts.getOrDefault(qt, 0) + 1);
		}

		Set<Integer> allDocIds = new HashSet<Integer>();
		for (Set<Integer> s : termInvertedList.values()) {
			allDocIds.addAll(s);
		}
		int docIdsCount = allDocIds.size();

		// Each priority queue corresponds to a query term: The p-queue itself stores
		// all terms in the reranking pool and their reranking scores to the query term.
		List<PriorityQueue<Pair<String, Double>>> allTermScoresPQ = new ArrayList<PriorityQueue<Pair<String, Double>>>();
		for (Map.Entry<String, Integer> q : queryTermsCounts.entrySet()) {
			String queryTerm = q.getKey();
			long df = reader.docFreq(new Term(fieldName, queryTerm));
			if (df == 0L) {
				continue;
			}
			float idf = (float) Math.log((1 + docCount) / df);
			int qtf = q.getValue();
			if (termInvertedList.containsKey(queryTerm)) {
				PriorityQueue<Pair<String, Double>> termScorePQ = new PriorityQueue<Pair<String, Double>>(
				    new ScoreComparator());
				double selfMI = computeMutualInformation(termInvertedList.get(queryTerm), termInvertedList.get(queryTerm),
				    docIdsCount);
				for (Map.Entry<String, Set<Integer>> termEntry : termInvertedList.entrySet()) {
					double score;
					if (termEntry.getKey().equals(queryTerm)) { // The mutual information to itself will always be 1
						score = idf * qtf;
					} else {
						double crossMI = computeMutualInformation(termInvertedList.get(queryTerm), termEntry.getValue(),
						    docIdsCount);
						score = idf * beta * qtf * crossMI / selfMI;
					}
					termScorePQ.add(Pair.of(termEntry.getKey(), score));
				}
				allTermScoresPQ.add(termScorePQ);
			}
		}

		Map<String, Double> aggTermScores = new HashMap<String, Double>();
		for (PriorityQueue<Pair<String, Double>> termScores : allTermScoresPQ) {
			for (int i = 0; i < Math.min(termScores.size(), Math.max(M, K)); i++) {
				Pair<String, Double> termScore = termScores.poll();
				String term = termScore.getLeft();
				Double score = termScore.getRight();
				if (score - 0.0 > 1e-8) {
					aggTermScores.put(term, aggTermScores.getOrDefault(term, 0.0) + score);
				}
			}
		}
		PriorityQueue<Pair<String, Double>> termScoresPQ = new PriorityQueue<Pair<String, Double>>(new ScoreComparator());
		for (Map.Entry<String, Double> termScore : aggTermScores.entrySet()) {
			termScoresPQ.add(Pair.of(termScore.getKey(), termScore.getValue() / queryTerms.size()));
		}
		Map<String, Double> resultTermScores = new HashMap<String, Double>();
		for (int i = 0; i < Math.min(termScoresPQ.size(), M); i++) {
			Pair<String, Double> termScore = termScoresPQ.poll();
			String term = termScore.getKey();
			double score = termScore.getValue();
			resultTermScores.put(term, score);
		}

		return resultTermScores;
	}

	private double computeMutualInformation(Set<Integer> docidsX, Set<Integer> docidsY, int totalDocCount) {
		int x1 = docidsX.size(), y1 = docidsY.size(); // num docs where x occurs
		int x0 = totalDocCount - x1, y0 = totalDocCount - y1; // num docs where x does not occur

		if (x1 == 0 || x0 == 0 || y1 == 0 || y0 == 0) {
			return 0;
		}

		float pX0 = 1.0f * x0 / totalDocCount;
		float pX1 = 1.0f * x1 / totalDocCount;
		float pY0 = 1.0f * y0 / totalDocCount;
		float pY1 = 1.0f * y1 / totalDocCount;

		// get the intersection of docIds
		Set<Integer> docidsXClone = new HashSet<Integer>(docidsX); // directly operate on docidsX will change it permanently
		docidsXClone.retainAll(docidsY);
		int numXY11 = docidsXClone.size();
		int numXY10 = x1 - numXY11; // doc num that x occurs but y doesn't
		int numXY01 = y1 - numXY11; // doc num that y occurs but x doesn't
		int numXY00 = totalDocCount - numXY11 - numXY10 - numXY01; // doc num that neither x nor y occurs

		float pXY11 = 1.0f * numXY11 / totalDocCount;
		float pXY10 = 1.0f * numXY10 / totalDocCount;
		float pXY01 = 1.0f * numXY01 / totalDocCount;
		float pXY00 = 1.0f * numXY00 / totalDocCount;

		double m00 = 0, m01 = 0, m10 = 0, m11 = 0;
		if (pXY00 != 0)
			m00 = pXY00 * Math.log(pXY00 / (pX0 * pY0));
		if (pXY01 != 0)
			m01 = pXY01 * Math.log(pXY01 / (pX0 * pY1));
		if (pXY10 != 0)
			m10 = pXY10 * Math.log(pXY10 / (pX1 * pY0));
		if (pXY11 != 0)
			m11 = pXY11 * Math.log(pXY11 / (pX1 * pY1));
		return m00 + m10 + m01 + m11;
	}
}
