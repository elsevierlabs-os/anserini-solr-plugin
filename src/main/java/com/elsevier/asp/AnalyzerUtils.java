package com.elsevier.asp;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;

public class AnalyzerUtils {

	public static List<String> tokenizeQuery(String queryString, String fieldName, Analyzer analyzer) {
		List<String> queryTokens = new ArrayList<String>();
		try {
			TokenStream tokenStream = analyzer.tokenStream(fieldName, queryString);
			CharTermAttribute termAttr = tokenStream.getAttribute(CharTermAttribute.class);
			tokenStream.reset();
			while (tokenStream.incrementToken()) {
				String token = termAttr.toString();
				if (token.length() == 0) continue;
				queryTokens.add(token);
			}
			tokenStream.end();
			tokenStream.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
		return queryTokens;
	}

}
