package com.movierec.model;

import static com.movierec.math.Num.round;

import com.movierec.dataset.MovieData;
import com.movierec.math.CsrMatrix;
import com.movierec.math.Num;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Offline evaluation: how good are these recommendations, really?
 *
 * <p>Two very different questions get answered here, because a model can be excellent at one and
 * poor at the other:
 *
 * <ol>
 *   <li>Rating accuracy: if it predicts 4.2, was the real rating close? Measured with RMSE and MAE
 *       on a held-out temporal split.
 *   <li>Ranking quality: are the items it puts at the top actually the ones the user went on to
 *       like? Measured with Precision@K, Recall@K, MAP@K and NDCG@K, treating a held-out rating of
 *       4.0 or more as relevant.
 * </ol>
 *
 * <p>Accuracy is not the whole story either, so coverage, novelty and intra-list diversity are
 * reported too: a model that recommends the same 20 blockbusters to everyone can score well on
 * precision while being useless in production.
 *
 * <p>All models are evaluated on the training split only, then scored against the held-out ratings
 * they never saw.
 */
public final class Evaluator {

    private static final double RELEVANT_AT = 4.0;
    private static final double LOG2 = Math.log(2.0);

    /** A scorer maps a user row index to a score for every item. */
    @FunctionalInterface
    interface Scorer {
        double[] score(int u);
    }

    private record ModelSpec(String name, String label, String family, Scorer rank, Scorer rate) {}

    private final MovieData data;
    private final ContentBasedRecommender content;
    private final ItemKnn itemKnn;
    private final UserKnn userKnn;
    private final BiasSvd svd;
    private final Baseline baseline;
    private final double alpha;

    public Evaluator(MovieData data, ContentBasedRecommender content, ItemKnn itemKnn, UserKnn userKnn,
                     BiasSvd svd, Baseline baseline, double alpha) {
        this.data = data;
        this.content = content;
        this.itemKnn = itemKnn;
        this.userKnn = userKnn;
        this.svd = svd;
        this.baseline = baseline;
        this.alpha = alpha;
    }

    // --------------------------------------------------------- rating accuracy
    /** RMSE / MAE over every held-out (user, item, rating) triple. */
    private Map<String, Object> ratingAccuracy(Scorer scorer) {
        double squared = 0.0;
        double absolute = 0.0;
        int count = 0;
        for (Map.Entry<Integer, Map<Integer, Double>> entry : data.testByUser.entrySet()) {
            Integer u = data.userPos.get(entry.getKey());
            if (u == null || entry.getValue().isEmpty()) {
                continue;
            }
            double[] preds = scorer.score(u);
            for (Map.Entry<Integer, Double> held : entry.getValue().entrySet()) {
                Integer pos = data.itemPos.get(held.getKey());
                if (pos != null) {
                    double err = preds[pos] - held.getValue();
                    squared += err * err;
                    absolute += Math.abs(err);
                    count++;
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rmse", count == 0 ? null : (Object) round(Math.sqrt(squared / count), 4));
        out.put("mae", count == 0 ? null : (Object) round(absolute / count, 4));
        out.put("n", count);
        return out;
    }

    // --------------------------------------------------------- ranking quality
    /** Precision / Recall / MAP / NDCG at k, plus coverage, novelty, diversity. */
    private Map<String, Object> rankingQuality(Scorer scorer, int k) {
        CsrMatrix train = data.train;
        int[] popularity = train.columnCounts();
        double[] selfInfo = new double[popularity.length];
        for (int i = 0; i < popularity.length; i++) {
            double probability = Math.max(popularity[i], 1) / (double) Math.max(1, data.nUsers());
            selfInfo[i] = -Math.log(probability) / LOG2; // rarer item -> higher novelty
        }

        double precision = 0.0;
        double recall = 0.0;
        double map = 0.0;
        double ndcg = 0.0;
        double hitRate = 0.0;
        double novelty = 0.0;
        double diversity = 0.0;
        int nLists = 0;
        Set<Integer> recommended = new HashSet<>();

        for (Map.Entry<Integer, Map<Integer, Double>> entry : data.testByUser.entrySet()) {
            Integer u = data.userPos.get(entry.getKey());
            if (u == null || entry.getValue().isEmpty()) {
                continue;
            }
            Set<Integer> relevant = new HashSet<>();
            entry.getValue().forEach((movieId, rating) -> {
                Integer pos = data.itemPos.get(movieId);
                if (rating >= RELEVANT_AT && pos != null) {
                    relevant.add(pos);
                }
            });
            if (relevant.isEmpty()) {
                continue;
            }

            double[] scores = scorer.score(u).clone();
            for (int p = train.indptr[u]; p < train.indptr[u + 1]; p++) {
                // never recommend something already in the training set
                scores[train.indices[p]] = Double.NEGATIVE_INFINITY;
            }
            int[] top = Num.topN(scores, k);

            int hits = 0;
            double dcg = 0.0;
            double sumPrecisions = 0.0;
            for (int r = 0; r < top.length; r++) {
                if (relevant.contains(top[r])) {
                    hits++;
                    dcg += 1.0 / (Math.log(r + 2) / LOG2);
                    sumPrecisions += hits / (double) (r + 1);
                }
            }
            precision += hits / (double) k;
            recall += hits / (double) relevant.size();
            hitRate += hits > 0 ? 1.0 : 0.0;

            double idcg = 0.0;
            for (int r = 0; r < Math.min(k, relevant.size()); r++) {
                idcg += 1.0 / (Math.log(r + 2) / LOG2);
            }
            ndcg += idcg > 0 ? dcg / idcg : 0.0;
            map += hits > 0 ? sumPrecisions / Math.min(relevant.size(), k) : 0.0;

            double listNovelty = 0.0;
            for (int item : top) {
                recommended.add(item);
                listNovelty += selfInfo[item];
            }
            novelty += listNovelty / top.length;
            if (top.length > 1) {
                diversity += 1.0 - content.meanPairwiseSimilarity(top);
            }
            nLists++;
        }

        Map<String, Object> out = new LinkedHashMap<>();
        if (nLists == 0) {
            return out;
        }
        out.put("k", k);
        out.put("precision", round(precision / nLists, 4));
        out.put("recall", round(recall / nLists, 4));
        out.put("map", round(map / nLists, 4));
        out.put("ndcg", round(ndcg / nLists, 4));
        out.put("hit_rate", round(hitRate / nLists, 4));
        out.put("coverage", round(recommended.size() / (double) data.nItems() * 100, 2));
        out.put("novelty", round(novelty / nLists, 3));
        out.put("diversity", round(diversity / nLists, 4));
        out.put("n_users", nLists);
        return out;
    }

    // ------------------------------------------------------------ the scorers
    private double[] contentScores(int u) {
        CsrMatrix train = data.train;
        Map<Integer, Double> rated = new LinkedHashMap<>();
        for (int p = train.indptr[u]; p < train.indptr[u + 1]; p++) {
            rated.put(data.itemIds[train.indices[p]], train.data[p]);
        }
        return content.scoreAll(content.buildProfile(rated));
    }

    /**
     * Content similarity mapped onto the rating scale so RMSE is meaningful.
     *
     * <p>Cosine lives in [0, 1] and knows nothing about how generously this user rates, so it is
     * rescaled around the user's own mean and spread.
     */
    private double[] contentRatings(int u) {
        CsrMatrix train = data.train;
        double[] sims = contentScores(u);
        int lo = train.indptr[u];
        int hi = train.indptr[u + 1];
        double[] out = new double[data.nItems()];
        if (hi == lo) {
            Arrays.fill(out, 3.5);
            return out;
        }
        double[] vals = Arrays.copyOfRange(train.data, lo, hi);
        // float32 in the original, because the rating matrix is stored as float32
        float mean = (float) Num.mean(vals);
        double squares = 0.0;
        for (double v : vals) {
            float d = (float) v - mean;
            squares += d * d;
        }
        double std = (float) Math.sqrt((float) (squares / vals.length));

        double[] ratedSims = new double[hi - lo];
        for (int p = lo; p < hi; p++) {
            ratedSims[p - lo] = sims[train.indices[p]];
        }
        double sMean = Num.mean(ratedSims);
        double sStd = Num.std(ratedSims);
        if (sStd == 0.0) {
            sStd = 1.0;
        }
        for (int i = 0; i < out.length; i++) {
            double z = (sims[i] - sMean) / sStd;
            out[i] = Num.clip(mean + z * Math.max(std, 0.4), 0.5, 5.0);
        }
        return out;
    }

    private static double[] normalise(double[] values) {
        double lo = Double.POSITIVE_INFINITY;
        double hi = Double.NEGATIVE_INFINITY;
        for (double v : values) {
            lo = Math.min(lo, v);
            hi = Math.max(hi, v);
        }
        double[] out = new double[values.length];
        if (hi - lo < 1e-12) {
            Arrays.fill(out, 0.5);
            return out;
        }
        for (int i = 0; i < out.length; i++) {
            out[i] = (values[i] - lo) / (hi - lo);
        }
        return out;
    }

    private double[] blend(double[] cf, double[] other) {
        double[] out = new double[cf.length];
        for (int i = 0; i < out.length; i++) {
            out[i] = alpha * cf[i] + (1 - alpha) * other[i];
        }
        return out;
    }

    private List<ModelSpec> models() {
        Scorer itemRank = u -> itemKnn.rankAll(u, null);
        Scorer itemRate = u -> itemKnn.scoreAll(u, null)[0];
        Scorer svdRank = u -> svd.rankAll(svd.userFactors(u));
        Scorer svdRate = u -> svd.scoreAll(svd.userFactors(u));
        return List.of(
                new ModelSpec("baseline", "Bias baseline", "baseline", baseline::rankAll, baseline::scoreAll),
                new ModelSpec("content", "Content-based (TF-IDF)", "content", this::contentScores, this::contentRatings),
                new ModelSpec("item-knn", "Item-based kNN", "collaborative", itemRank, itemRate),
                new ModelSpec("user-knn", "User-based kNN", "collaborative",
                        u -> userKnn.rankAll(u, null), u -> userKnn.scoreAll(u, null)[0]),
                new ModelSpec("svd", "Matrix factorisation (SVD)", "collaborative", svdRank, svdRate),
                new ModelSpec("hybrid", "Hybrid (content + item-kNN)", "hybrid",
                        u -> blend(normalise(itemRank.score(u)), normalise(contentScores(u))),
                        u -> blend(itemRate.score(u), contentRatings(u))),
                new ModelSpec("hybrid-svd", "Hybrid (content + SVD)", "hybrid",
                        u -> blend(normalise(svdRank.score(u)), normalise(contentScores(u))),
                        u -> blend(svdRate.score(u), contentRatings(u))));
    }

    // ----------------------------------------------------------- orchestration
    /** Evaluates every model on the same split and returns one comparable table. */
    public Map<String, Object> runFull(int k) {
        long started = System.nanoTime();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ModelSpec spec : models()) {
            long t0 = System.nanoTime();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("model", spec.name());
            row.put("label", spec.label());
            row.put("family", spec.family());
            row.putAll(ratingAccuracy(spec.rate()));
            row.putAll(rankingQuality(spec.rank(), k));
            row.put("seconds", round((System.nanoTime() - t0) / 1e9, 2));
            rows.add(row);
        }
        int testRatings = data.testByUser.values().stream().mapToInt(Map::size).sum();
        Map<String, Object> split = new LinkedHashMap<>();
        split.put("train_ratings", data.train.nnz());
        split.put("test_ratings", testRatings);
        split.put("method", "per-user temporal holdout (newest 20% of each user's ratings)");
        split.put("relevance_threshold", RELEVANT_AT);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("k", k);
        out.put("rows", rows);
        out.put("split", split);
        out.put("total_seconds", round((System.nanoTime() - started) / 1e9, 2));
        return out;
    }
}
