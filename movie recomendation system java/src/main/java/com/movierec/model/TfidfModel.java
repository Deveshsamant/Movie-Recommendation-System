package com.movierec.model;

import com.movierec.math.CsrMatrix;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * TF-IDF document vectors, matching sklearn's
 * TfidfVectorizer(token_pattern="[^ ]+", sublinear_tf=True, min_df=2, max_df=0.6):
 *
 * <ul>
 *   <li>the vocabulary is sorted alphabetically and keeps terms in 2+ documents and at most 60%
 *   <li>tf = 1 + ln(count)
 *   <li>idf = ln((1 + n_docs) / (1 + df)) + 1
 *   <li>rows are L2-normalised, so a dot product is a cosine
 * </ul>
 */
final class TfidfModel {

    final String[] terms;
    final CsrMatrix matrix;

    private TfidfModel(String[] terms, CsrMatrix matrix) {
        this.terms = terms;
        this.matrix = matrix;
    }

    static TfidfModel fit(List<String> documents) {
        int nDocs = documents.size();
        List<Map<String, Integer>> counts = new ArrayList<>(nDocs);
        Map<String, Integer> documentFrequency = new HashMap<>();
        for (String document : documents) {
            Map<String, Integer> tally = new HashMap<>();
            for (String token : document.split(" ")) {
                if (!token.isEmpty()) {
                    tally.merge(token, 1, Integer::sum);
                }
            }
            counts.add(tally);
            tally.keySet().forEach(term -> documentFrequency.merge(term, 1, Integer::sum));
        }

        double maxDocCount = 0.6 * nDocs;
        TreeMap<String, Integer> kept = new TreeMap<>();
        documentFrequency.forEach((term, df) -> {
            if (df >= 2 && df <= maxDocCount) {
                kept.put(term, df);
            }
        });
        String[] terms = kept.keySet().toArray(new String[0]);
        Map<String, Integer> termIndex = new HashMap<>();
        double[] idf = new double[terms.length];
        for (int t = 0; t < terms.length; t++) {
            termIndex.put(terms[t], t);
            idf[t] = Math.log((1.0 + nDocs) / (1.0 + kept.get(terms[t]))) + 1.0;
        }

        int[] indptr = new int[nDocs + 1];
        int nnz = 0;
        for (Map<String, Integer> tally : counts) {
            for (String term : tally.keySet()) {
                if (termIndex.containsKey(term)) {
                    nnz++;
                }
            }
        }
        int[] indices = new int[nnz];
        double[] data = new double[nnz];
        int at = 0;
        for (int d = 0; d < nDocs; d++) {
            Map<String, Integer> tally = counts.get(d);
            int start = at;
            int[] columns = tally.keySet().stream()
                    .filter(termIndex::containsKey)
                    .mapToInt(termIndex::get)
                    .sorted()
                    .toArray();
            double norm = 0.0;
            for (int column : columns) {
                double value = (1.0 + Math.log(tally.get(terms[column]))) * idf[column];
                indices[at] = column;
                data[at] = value;
                norm += value * value;
                at++;
            }
            norm = Math.sqrt(norm);
            if (norm > 0) {
                for (int p = start; p < at; p++) {
                    data[p] /= norm;
                }
            }
            indptr[d + 1] = at;
        }
        return new TfidfModel(terms, new CsrMatrix(nDocs, terms.length, indptr,
                Arrays.copyOf(indices, at), Arrays.copyOf(data, at)));
    }
}
