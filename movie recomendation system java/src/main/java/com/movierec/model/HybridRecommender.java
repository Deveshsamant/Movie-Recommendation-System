package com.movierec.model;

import static com.movierec.math.Num.round;

import com.movierec.dataset.MovieData;
import com.movierec.math.Num;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Hybrid recommenders: four ways to combine content and collaborative signals.
 *
 * <p>The two families fail in opposite directions. Content-based filtering knows what a movie is but
 * never discovers anything outside your existing taste; collaborative filtering finds genuine
 * surprises but is helpless on a new user or a new film. Hybrids exist to cover each other's blind
 * spots.
 *
 * <p>Strategies implemented (Burke, 2002 taxonomy):
 *
 * <ul>
 *   <li>weighted: normalise both scores, blend linearly with a tunable alpha
 *   <li>switching: pick a model per user based on how much evidence exists
 *   <li>rank: reciprocal rank fusion, ignores score scales entirely
 *   <li>cascade: collaborative proposes a candidate set, content re-ranks it
 * </ul>
 */
public final class HybridRecommender {

    public static final List<String> STRATEGIES = List.of("weighted", "switching", "rank", "cascade");

    private static final double MASKED = -1e9;

    /** One blended film with both sources and how much each contributed. */
    public record Item(int pos, int movieId, double score, double contentRaw, double contentNorm,
                       double cfRaw, double cfNorm, double cfRating, double contentPart, double cfPart) {
        public String lead() {
            return cfPart >= contentPart ? "collaborative" : "content";
        }
    }

    public record Result(List<Item> items, Map<String, Object> trace) {}

    private record Scaled(double[] values, double lo, double hi) {}

    private record CfScores(double[] rank, double[] rating) {}

    private final MovieData data;
    private final ItemKnn itemKnn;
    private final UserKnn userKnn;
    private final BiasSvd svd;
    private final double defaultAlpha;
    private final int coldThreshold;
    private final int nItems;
    private volatile ContentBasedRecommender content;

    public HybridRecommender(MovieData data, ContentBasedRecommender content, ItemKnn itemKnn, UserKnn userKnn,
                             BiasSvd svd, Map<String, Object> cfg) {
        this.data = data;
        this.content = content;
        this.itemKnn = itemKnn;
        this.userKnn = userKnn;
        this.svd = svd;
        this.defaultAlpha = cfg.get("alpha") instanceof Number a ? a.doubleValue() : 0.55;
        this.coldThreshold = cfg.get("cold_start_threshold") instanceof Number t ? t.intValue() : 8;
        this.nItems = data.nItems();
    }

    public void setContent(ContentBasedRecommender content) {
        this.content = content;
    }

    /**
     * Scales to [0, 1], returning the bounds used so the UI can show exactly how a raw score became
     * a normalised one.
     */
    private static Scaled minmax(double[] values) {
        double lo = Double.POSITIVE_INFINITY;
        double hi = Double.NEGATIVE_INFINITY;
        for (double v : values) {
            if (!Double.isNaN(v) && !Double.isInfinite(v)) {
                lo = Math.min(lo, v);
                hi = Math.max(hi, v);
            }
        }
        double[] out = new double[values.length];
        if (lo > hi) {
            return new Scaled(out, 0.0, 1.0);
        }
        if (hi - lo < 1e-12) {
            Arrays.fill(out, 0.5);
            return new Scaled(out, lo, hi);
        }
        for (int i = 0; i < out.length; i++) {
            out[i] = (values[i] - lo) / (hi - lo);
        }
        return new Scaled(out, lo, hi);
    }

    // ------------------------------------------------------------------- parts
    private double[] contentScores(Map<Integer, Double> ratedByPos) {
        Map<Integer, Double> ratedIds = new LinkedHashMap<>();
        ratedByPos.forEach((pos, rating) -> ratedIds.put(data.itemIds[pos], rating));
        ContentBasedRecommender model = content;
        return model.scoreAll(model.buildProfile(ratedIds));
    }

    /**
     * The ranking signal and predicted rating from the chosen collaborative model.
     *
     * <p>These are deliberately two different numbers: ranking uses the raw evidence strength, while
     * the predicted rating is the normalised, clipped value a user would recognise. Blending happens
     * on the ranking signal.
     */
    private CfScores cfScores(Integer u, Map<Integer, Double> ratedByPos, String cfModel) {
        if (cfModel.equals("svd")) {
            BiasSvd.Factors viewer = u != null ? svd.userFactors(u) : svd.foldIn(ratedByPos);
            return new CfScores(svd.rankAll(viewer), svd.scoreAll(viewer));
        }
        Map<Integer, Double> ratings = u != null ? null : ratedByPos;
        if (cfModel.equals("user-knn")) {
            return new CfScores(userKnn.rankAll(u, ratings), userKnn.scoreAll(u, ratings)[0]);
        }
        return new CfScores(itemKnn.rankAll(u, ratings), itemKnn.scoreAll(u, ratings)[0]);
    }

    // ---------------------------------------------------------------- strategy
    public Result recommend(Integer u, Map<Integer, Double> ratedByPos, int n, String strategy, String cfModel, Double alphaOverride) {
        double alpha = alphaOverride == null ? defaultAlpha : alphaOverride;
        int nRatings = ratedByPos.size();

        double[] contentRaw = contentScores(ratedByPos);
        CfScores cf = cfScores(u, ratedByPos, cfModel);
        Scaled contentNorm = minmax(contentRaw);
        Scaled cfNorm = minmax(cf.rank());

        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("strategy", strategy);
        trace.put("cf_model", cfModel);
        trace.put("alpha", alpha);
        trace.put("n_ratings", nRatings);
        trace.put("content_range", List.of(round(contentNorm.lo(), 4), round(contentNorm.hi(), 4)));
        trace.put("cf_range", List.of(round(cfNorm.lo(), 4), round(cfNorm.hi(), 4)));

        double[] scores = new double[nItems];
        double effectiveAlpha;
        switch (strategy) {
            case "switching" -> {
                boolean cold = nRatings < coldThreshold;
                scores = (cold ? contentNorm : cfNorm).values().clone();
                effectiveAlpha = cold ? 0.0 : 1.0;
                trace.put("switched_to", cold ? "content" : "collaborative");
                trace.put("cold_start", cold);
                trace.put("threshold", coldThreshold);
                trace.put("reason", cold
                        ? String.format("only %d ratings (< %d) - collaborative evidence is too thin, "
                                + "falling back to metadata", nRatings, coldThreshold)
                        : String.format("%d ratings (>= %d) - enough overlap with other users to trust "
                                + "collaborative signal", nRatings, coldThreshold));
            }
            case "rank" -> {
                double kRrf = 60.0;
                double[] contentRank = ranks(contentRaw);
                double[] cfRank = ranks(cf.rank());
                double max = 0.0;
                for (int i = 0; i < nItems; i++) {
                    scores[i] = alpha * (1.0 / (kRrf + cfRank[i])) + (1 - alpha) * (1.0 / (kRrf + contentRank[i]));
                    max = Math.max(max, scores[i]);
                }
                if (max > 0) {
                    for (int i = 0; i < nItems; i++) {
                        scores[i] /= max;
                    }
                }
                effectiveAlpha = alpha;
                trace.put("k_rrf", kRrf);
            }
            case "cascade" -> {
                int poolSize = Math.max(n * 12, 150);
                double[] cfMasked = cf.rank().clone();
                ratedByPos.keySet().forEach(pos -> cfMasked[pos] = MASKED);
                Arrays.fill(scores, MASKED);
                for (int pos : Num.topN(cfMasked, poolSize)) {
                    scores[pos] = contentNorm.values()[pos];
                }
                effectiveAlpha = 0.0;
                trace.put("pool_size", poolSize);
                trace.put("reason", String.format("collaborative filtering shortlisted %d candidates; "
                        + "content similarity then re-ranked them", poolSize));
            }
            default -> { // weighted
                for (int i = 0; i < nItems; i++) {
                    scores[i] = alpha * cfNorm.values()[i] + (1 - alpha) * contentNorm.values()[i];
                }
                effectiveAlpha = alpha;
            }
        }

        double[] masked = scores;
        ratedByPos.keySet().forEach(pos -> masked[pos] = MASKED);

        List<Item> items = new ArrayList<>();
        for (int pos : Num.topN(masked, n)) {
            if (masked[pos] <= -1e8) {
                continue;
            }
            items.add(new Item(pos, data.itemIds[pos], masked[pos],
                    contentRaw[pos], contentNorm.values()[pos],
                    cf.rank()[pos], cfNorm.values()[pos], cf.rating()[pos],
                    (1 - effectiveAlpha) * contentNorm.values()[pos],
                    effectiveAlpha * cfNorm.values()[pos]));
        }
        trace.put("effective_alpha", effectiveAlpha);
        return new Result(items, trace);
    }

    private double[] ranks(double[] values) {
        int[] order = Num.argsortDescending(values);
        double[] rank = new double[values.length];
        for (int r = 0; r < order.length; r++) {
            rank[order[r]] = r;
        }
        return rank;
    }

    /**
     * How the top-N list churns as alpha slides from pure content to pure CF.
     *
     * <p>This is the clearest way to see that the two models genuinely disagree: if they agreed, the
     * list would not change. Each step's items are {movieId, score}.
     */
    public List<Map<String, Object>> alphaSweep(Integer u, Map<Integer, Double> ratedByPos, String cfModel, int n) {
        Scaled contentNorm = minmax(contentScores(ratedByPos));
        Scaled cfNorm = minmax(cfScores(u, ratedByPos, cfModel).rank());

        List<Map<String, Object>> out = new ArrayList<>();
        for (double alpha : new double[] {0.0, 0.25, 0.5, 0.75, 1.0}) {
            double[] blended = new double[nItems];
            for (int i = 0; i < nItems; i++) {
                blended[i] = alpha * cfNorm.values()[i] + (1 - alpha) * contentNorm.values()[i];
            }
            ratedByPos.keySet().forEach(pos -> blended[pos] = MASKED);
            List<double[]> items = new ArrayList<>();
            for (int pos : Num.topN(blended, n)) {
                items.add(new double[] {data.itemIds[pos], round(blended[pos], 4)});
            }
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("alpha", alpha);
            step.put("items", items);
            out.add(step);
        }
        return out;
    }

    /** Top-N from content alone, CF alone and the hybrid, plus their overlap. */
    public Map<String, Object> compareSources(Integer u, Map<Integer, Double> ratedByPos, int n, String cfModel) {
        double[] contentRaw = contentScores(ratedByPos);
        double[] cfRaw = cfScores(u, ratedByPos, cfModel).rank();
        Scaled contentNorm = minmax(contentRaw);
        Scaled cfNorm = minmax(cfRaw);
        double[] blended = new double[nItems];
        for (int i = 0; i < nItems; i++) {
            blended[i] = defaultAlpha * cfNorm.values()[i] + (1 - defaultAlpha) * contentNorm.values()[i];
        }

        Map<String, List<Integer>> lists = new LinkedHashMap<>();
        lists.put("content", topIds(contentRaw, ratedByPos.keySet(), n));
        lists.put("collaborative", topIds(cfRaw, ratedByPos.keySet(), n));
        lists.put("hybrid", topIds(blended, ratedByPos.keySet(), n));

        Set<Integer> c = new HashSet<>(lists.get("content"));
        Set<Integer> f = new HashSet<>(lists.get("collaborative"));
        Set<Integer> h = new HashSet<>(lists.get("hybrid"));
        int contentVsCf = 0;
        for (int m : c) {
            if (f.contains(m)) {
                contentVsCf++;
            }
        }
        int fromContent = 0;
        int fromCf = 0;
        int fromBoth = 0;
        int fromNeither = 0;
        for (int m : h) {
            boolean inC = c.contains(m);
            boolean inF = f.contains(m);
            if (inC && inF) {
                fromBoth++;
            } else if (inC) {
                fromContent++;
            } else if (inF) {
                fromCf++;
            } else {
                fromNeither++;
            }
        }
        Map<String, Object> overlap = new LinkedHashMap<>();
        overlap.put("content_vs_cf", contentVsCf);
        overlap.put("hybrid_from_content_only", fromContent);
        overlap.put("hybrid_from_cf_only", fromCf);
        overlap.put("hybrid_from_both", fromBoth);
        overlap.put("hybrid_from_neither", fromNeither);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("lists", lists);
        out.put("overlap", overlap);
        return out;
    }

    private List<Integer> topIds(double[] values, Set<Integer> seen, int n) {
        double[] masked = values.clone();
        seen.forEach(pos -> masked[pos] = MASKED);
        List<Integer> ids = new ArrayList<>();
        for (int pos : Num.topN(masked, n)) {
            ids.add(data.itemIds[pos]);
        }
        return ids;
    }
}
