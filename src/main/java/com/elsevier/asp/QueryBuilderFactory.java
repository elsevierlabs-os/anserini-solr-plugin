package com.elsevier.asp;

import java.util.List;
import java.util.Map;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.BoostQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.spans.SpanNearQuery;
import org.apache.lucene.search.spans.SpanQuery;
import org.apache.lucene.search.spans.SpanTermQuery;

public class QueryBuilderFactory {
	
	public Query buildBagOfWordsQuery(String queryString, String[] filters, 
			String fieldName, Analyzer analyzer) {
		List<String> tokens = AnalyzerUtils.tokenizeQuery(queryString, fieldName, analyzer);
		BooleanQuery.Builder builder = new BooleanQuery.Builder();
		for (String token : tokens) {
			builder.add(new TermQuery(new Term(fieldName, token)), BooleanClause.Occur.SHOULD);
		}
		return applyFilters(builder.build(), filters);
	}
	
	public Query buildSeqDepModelQuery(String queryString, String[] filters, 
			String fieldName, Analyzer analyzer, Map<String,Float> params) {
		List<String> tokens = AnalyzerUtils.tokenizeQuery(queryString, fieldName, analyzer);
		// terms component
		BooleanQuery.Builder termsBuilder = new BooleanQuery.Builder();
		for (String token : tokens) {
			termsBuilder.add(new TermQuery(new Term(fieldName, token)), BooleanClause.Occur.SHOULD);
		}
		if (tokens.size() == 1) {
			return termsBuilder.build();
		}
		// pairs components
		BooleanQuery.Builder orderedWindowBuilder = new BooleanQuery.Builder();
		BooleanQuery.Builder unorderedWindowBuilder = new BooleanQuery.Builder();
		for (int i = 0; i < tokens.size() - 1; i++) {
			SpanTermQuery t1 = new SpanTermQuery(new Term(fieldName, tokens.get(i)));
			SpanTermQuery t2 = new SpanTermQuery(new Term(fieldName, tokens.get(i+1)));
			SpanNearQuery orderedQuery = new SpanNearQuery(new SpanQuery[] {t1, t2}, 1, true);
			SpanNearQuery unorderedQuery = new SpanNearQuery(new SpanQuery[] {t1, t2}, 8, false);
			
			orderedWindowBuilder.add(orderedQuery, BooleanClause.Occur.SHOULD);
			unorderedWindowBuilder.add(unorderedQuery, BooleanClause.Occur.SHOULD);
		}
		// weight different components according to params
		BooleanQuery.Builder builder = new BooleanQuery.Builder();
		builder.add(new BoostQuery(termsBuilder.build(), 
				params.get("termWeight")), BooleanClause.Occur.SHOULD);
		builder.add(new BoostQuery(orderedWindowBuilder.build(), 
				params.get("orderedWindowWeight")), BooleanClause.Occur.SHOULD);
		builder.add(new BoostQuery(unorderedWindowBuilder.build(),
				params.get("unorderedWindowWeight")), BooleanClause.Occur.SHOULD);
		return applyFilters(builder.build(), filters);
	}
	
	private Query applyFilters(Query query, String[] filters) {
		if (filters == null || filters.length == 0) return query;
		BooleanQuery.Builder filterBuilder = new BooleanQuery.Builder();
		for (String nvp : filters) {
			System.out.println("nvp=" + nvp);
			String[] nvpElements = nvp.split(":");
			filterBuilder.add(new TermQuery(new Term(nvpElements[0], nvpElements[1])), 
					BooleanClause.Occur.SHOULD);
		}
		BooleanQuery.Builder filteredQueryBuilder = new BooleanQuery.Builder();
		filteredQueryBuilder.add(query, BooleanClause.Occur.MUST);
		filteredQueryBuilder.add(filterBuilder.build(), BooleanClause.Occur.FILTER);
		return filteredQueryBuilder.build();
	}
}
