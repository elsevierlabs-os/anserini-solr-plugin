package com.elsevier.asp;

import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.ScoreDoc;

public class RerankedResult {

	private BooleanQuery query;
	private ScoreDoc[] documents;
	private String errorMessage;

	
	public RerankedResult(BooleanQuery query, ScoreDoc[] documents, String errorMessage) {
		this.query = query;
		this.documents = documents;
		this.errorMessage = errorMessage;
	}

	public RerankedResult(BooleanQuery query, ScoreDoc[] documents) {
		this(query, documents, null);
	}
	
	public BooleanQuery getQuery() {
		return query;
	}
	
	public ScoreDoc[] getDocuments() {
		return documents;
	}

	public String getErrorMessage() {
		return errorMessage;
	}
}
