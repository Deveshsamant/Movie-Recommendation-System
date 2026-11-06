package com.movierec.model;

import com.movierec.math.CsrMatrix;
import com.movierec.math.Num;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the collaborative models share.
 *
 * <p>None of them look at movie metadata: everything comes from the rating pattern. Each model has a
 * score-everything path and a single-item explanation path, and the two produce the same number. A
 * tool that shows one calculation while ranking by another would be teaching a lie.
 */
public final class Cf {

    public static final double RATING_MIN = 0.5;
    public static final double RATING_MAX = 5.0;

    private Cf() {}

    /** One ranked film: its predicted rating, ranking score and the evidence behind it. */
    public record Rec(int pos, double predicted, double rank, double support) {}

    /** Significance shrinkage, n / (n + lambda). The original evaluates this in float32. */
    static double shrinkFactor(int common, double shrinkage) {
        float n = common;
        return n / (n + (float) shrinkage);
    }

    /** The items this viewer already rated: from their ratings, or from their row in R. */
    static Set<Integer> seenItems(CsrMatrix R, Integer u, Map<Integer, Double> ratings) {
        Set<Integer> seen = new HashSet<>();
        if (ratings != null) {
            seen.addAll(ratings.keySet());
        } else if (u != null) {
            for (int p = R.indptr[u]; p < R.indptr[u + 1]; p++) {
                seen.add(R.indices[p]);
            }
        }
        return seen;
    }

    /** Rank by evidence strength, but report the predicted rating alongside. */
    static List<Rec> rankedList(double[] rank, double[] predicted, double[] support, Set<Integer> seen, int n) {
        double[] scores = rank.clone();
        for (int item : seen) {
            scores[item] = Double.NEGATIVE_INFINITY;
        }
        List<Rec> out = new ArrayList<>();
        for (int i : Num.topN(scores, n)) {
            if (!Double.isInfinite(scores[i])) {
                out.add(new Rec(i, predicted[i], rank[i], support == null ? 1.0 : support[i]));
            }
        }
        return out;
    }
}
