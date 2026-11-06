package com.movierec.model;

import static com.movierec.math.Num.round;

import com.movierec.math.CsrMatrix;
import com.movierec.math.Num;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

/**
 * Pearson-correlation user neighbourhoods with significance shrinkage (Resnick et al., 1994).
 *
 * <p>A fixed neighbourhood of the k most similar users is chosen once per user; every prediction
 * then uses whichever of those neighbours rated the item:
 *
 * <pre>r_hat(u,i) = mean_u + sum_v sim(u,v) * (r_vi - mean_v) / sum_v |sim(u,v)|</pre>
 */
public final class UserKnn {

    public final int k;
    public final double shrinkage;

    private final CsrMatrix R;
    private final CsrMatrix byItem;
    private final CsrMatrix centred;
    private final int nUsers;
    private final int nItems;
    private final double[] userMean;
    private final double[] norms;
    private final double[][] S;
    private final int[][] common;

    private record Neighbourhood(int[] users, double[] weights) {}

    private record ViewerSims(double[] sims, double mean) {}

    public UserKnn(CsrMatrix R, int k, double shrinkage) {
        this.R = R;
        this.byItem = R.transpose();
        this.k = k;
        this.shrinkage = shrinkage;
        this.nUsers = R.nRows;
        this.nItems = R.nCols;
        this.userMean = R.rowMeans();

        double[] centredData = new double[R.nnz()];
        double[] unit = new double[R.nnz()];
        this.norms = new double[nUsers];
        for (int u = 0; u < nUsers; u++) {
            double sum = 0.0;
            for (int p = R.indptr[u]; p < R.indptr[u + 1]; p++) {
                centredData[p] = R.data[p] - userMean[u];
                sum += centredData[p] * centredData[p];
            }
            norms[u] = Math.max(Math.sqrt(sum), 1e-9);
            for (int p = R.indptr[u]; p < R.indptr[u + 1]; p++) {
                unit[p] = centredData[p] / norms[u];
            }
        }
        this.centred = new CsrMatrix(nUsers, nItems, R.indptr, R.indices, centredData);

        CsrMatrix unitColumns = new CsrMatrix(nUsers, nItems, R.indptr, R.indices, unit).transpose();
        this.S = new double[nUsers][nUsers];
        this.common = new int[nUsers][nUsers];
        IntStream.range(0, nUsers).parallel().forEach(u -> {
            double[] sims = S[u];
            int[] shared = common[u];
            for (int p = R.indptr[u]; p < R.indptr[u + 1]; p++) {
                int item = R.indices[p];
                double value = unit[p];
                for (int q = unitColumns.indptr[item]; q < unitColumns.indptr[item + 1]; q++) {
                    sims[unitColumns.indices[q]] += value * unitColumns.data[q];
                    shared[unitColumns.indices[q]]++;
                }
            }
            for (int v = 0; v < nUsers; v++) {
                sims[v] = v == u || shared[v] == 0 ? 0.0 : sims[v] * Cf.shrinkFactor(shared[v], shrinkage);
            }
        });
    }

    private Neighbourhood neighbourhood(double[] sims) {
        int[] users = java.util.Arrays.stream(Num.topN(sims, k)).filter(v -> sims[v] > 0).toArray();
        double[] weights = new double[users.length];
        for (int i = 0; i < users.length; i++) {
            weights[i] = sims[users[i]];
        }
        return new Neighbourhood(users, weights);
    }

    private ViewerSims simsFor(Integer u, Map<Integer, Double> ratings) {
        if (ratings == null) {
            return new ViewerSims(S[u], userMean[u]);
        }
        double mean = ratings.isEmpty() ? 3.5
                : Num.mean(ratings.values().stream().mapToDouble(Double::doubleValue).toArray());
        double[] vec = new double[nItems];
        ratings.forEach((item, rating) -> vec[item] = rating - mean);
        double norm = Math.max(Num.norm(vec), 1e-9);

        double[] sims = centred.multiply(vec);
        int[] shared = new int[nUsers];
        for (int item : ratings.keySet()) {
            for (int q = byItem.indptr[item]; q < byItem.indptr[item + 1]; q++) {
                shared[byItem.indices[q]]++;
            }
        }
        for (int v = 0; v < nUsers; v++) {
            sims[v] = sims[v] / (norms[v] * norm) * Cf.shrinkFactor(shared[v], shrinkage);
        }
        return new ViewerSims(sims, mean);
    }

    /** Predicted rating for every item [0], plus the neighbour support behind it [1]. */
    public double[][] scoreAll(Integer u, Map<Integer, Double> ratings) {
        ViewerSims viewer = simsFor(u, ratings);
        Neighbourhood nb = neighbourhood(viewer.sims());
        double[] numer = new double[nItems];
        double[] denom = new double[nItems];
        double[] preds = new double[nItems];
        if (nb.users().length == 0) {
            java.util.Arrays.fill(preds, viewer.mean());
            return new double[][] {preds, denom};
        }
        accumulate(nb, numer, denom);
        for (int i = 0; i < nItems; i++) {
            double raw = denom[i] > 1e-9 ? viewer.mean() + numer[i] / Math.max(denom[i], 1e-9) : viewer.mean();
            preds[i] = Num.clip(raw, Cf.RATING_MIN, Cf.RATING_MAX);
        }
        return new double[][] {preds, denom};
    }

    /** The un-normalised neighbour-weighted sum: see {@link ItemKnn#rankAll}. */
    public double[] rankAll(Integer u, Map<Integer, Double> ratings) {
        Neighbourhood nb = neighbourhood(simsFor(u, ratings).sims());
        double[] numer = new double[nItems];
        accumulate(nb, numer, new double[nItems]);
        return numer;
    }

    private void accumulate(Neighbourhood nb, double[] numer, double[] denom) {
        for (int n = 0; n < nb.users().length; n++) {
            int v = nb.users()[n];
            double weight = nb.weights()[n];
            for (int p = R.indptr[v]; p < R.indptr[v + 1]; p++) {
                numer[R.indices[p]] += weight * (R.data[p] - userMean[v]);
                denom[R.indices[p]] += Math.abs(weight);
            }
        }
    }

    /** The exact arithmetic behind one predicted rating. */
    public Map<String, Object> explain(int target, Integer u, Map<Integer, Double> ratings) {
        ViewerSims viewer = simsFor(u, ratings);
        Neighbourhood nb = neighbourhood(viewer.sims());
        double mean = viewer.mean();

        List<Map<String, Object>> neighbours = new ArrayList<>();
        double numer = 0.0;
        double denom = 0.0;
        for (int n = 0; n < nb.users().length; n++) {
            int v = nb.users()[n];
            double theirRating = R.get(v, target);
            if (theirRating <= 0) {
                continue;
            }
            double weight = nb.weights()[n];
            double dev = theirRating - userMean[v];
            numer += weight * dev;
            denom += Math.abs(weight);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("user_pos", v);
            row.put("similarity", round(weight, 4));
            row.put("their_rating", round(theirRating, 2));
            row.put("their_mean", round(userMean[v], 2));
            row.put("deviation", round(dev, 3));
            row.put("co_rated", u != null ? (Object) common[u][v] : null);
            row.put("term", round(weight * dev, 4));
            neighbours.add(row);
        }

        int nRaters = byItem.rowLength(target);
        Map<String, Object> out = new LinkedHashMap<>();
        if (neighbours.isEmpty()) {
            out.put("neighbours", List.of());
            out.put("numerator", 0.0);
            out.put("denominator", 0.0);
            out.put("user_mean", round(mean, 3));
            out.put("k_used", 0);
            out.put("n_raters", nRaters);
            return out;
        }
        int used = neighbours.size();
        double raw = denom > 0 ? mean + numer / denom : mean;
        neighbours.sort(Comparator.comparingDouble(row -> -Math.abs((double) row.get("term"))));
        out.put("neighbours", new ArrayList<>(neighbours.subList(0, Math.min(8, used))));
        out.put("numerator", round(numer, 4));
        out.put("denominator", round(denom, 4));
        out.put("user_mean", round(mean, 3));
        out.put("k_used", used);
        out.put("n_raters", nRaters);
        out.put("raw", round(raw, 4));
        return out;
    }

    public List<Cf.Rec> recommend(Integer u, Map<Integer, Double> ratings, int n) {
        double[] rank = rankAll(u, ratings);
        double[][] scored = scoreAll(u, ratings);
        return Cf.rankedList(rank, scored[0], scored[1], Cf.seenItems(R, u, ratings), n);
    }

    public List<Map<String, Object>> similarUsers(Integer u, Map<Integer, Double> ratings, int n) {
        double[] sims = simsFor(u, ratings).sims();
        List<Map<String, Object>> out = new ArrayList<>();
        for (int v : Num.topN(sims, n)) {
            if (sims[v] <= 0) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("user_pos", v);
            row.put("similarity", round(sims[v], 4));
            row.put("co_rated", u != null ? (Object) common[u][v] : null);
            row.put("n_ratings", R.rowLength(v));
            row.put("mean", round(userMean[v], 2));
            out.add(row);
        }
        return out;
    }
}
