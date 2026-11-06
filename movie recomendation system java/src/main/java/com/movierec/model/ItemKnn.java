package com.movierec.model;

import static com.movierec.math.Num.round;

import com.movierec.math.CsrMatrix;
import com.movierec.math.Num;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

/**
 * Adjusted-cosine item similarity with significance shrinkage (Sarwar et al., 2001).
 *
 * <p>Ratings are centred per user before the cosine, which cancels the "this user rates everything
 * a 4" effect. Similarities backed by few co-raters are shrunk toward zero:
 * sim' = sim * n_common / (n_common + lambda).
 *
 * <p>Prediction for user u, item i, using the k stored neighbours of i that u rated:
 *
 * <pre>r_hat(u,i) = mean_u + sum_j sim(i,j) * (r_uj - mean_u) / sum_j |sim(i,j)|</pre>
 */
public final class ItemKnn {

    public final int k;
    public final double shrinkage;
    /** Item x item similarities. Only positive values are kept, so |S| == S. */
    public final CsrMatrix S;

    private final CsrMatrix R;
    private final int nItems;

    private record ViewerProfile(double mean, double[] dev, double[] mask) {}

    private ItemKnn(CsrMatrix R, int k, double shrinkage, CsrMatrix S) {
        this.R = R;
        this.k = k;
        this.shrinkage = shrinkage;
        this.nItems = R.nCols;
        this.S = S != null ? S : buildSimilarity();
    }

    /** Trains once, reuses afterwards: keyed by matrix shape and hyper-parameters. */
    public static ItemKnn cached(CsrMatrix R, int k, double shrinkage, String tag, Path cacheDir) {
        Path path = cacheDir.resolve(String.format("itemknn_%s_%d_%d_%d_%d_%d.bin",
                tag, R.nRows, R.nCols, R.nnz(), k, (int) shrinkage));
        if (Files.exists(path)) {
            try {
                return new ItemKnn(R, k, shrinkage, readMatrix(path));
            } catch (IOException e) {
                System.err.println("retraining item-kNN, cache unreadable: " + e.getMessage());
            }
        }
        ItemKnn model = new ItemKnn(R, k, shrinkage, null);
        try {
            writeMatrix(path, model.S);
        } catch (IOException e) {
            System.err.println("could not cache item-kNN: " + e.getMessage());
        }
        return model;
    }

    private CsrMatrix buildSimilarity() {
        double[] means = R.rowMeans();
        double[] centred = new double[R.nnz()];
        double[] squares = new double[nItems];
        for (int u = 0; u < R.nRows; u++) {
            for (int p = R.indptr[u]; p < R.indptr[u + 1]; p++) {
                centred[p] = R.data[p] - means[u];
                squares[R.indices[p]] += centred[p] * centred[p];
            }
        }
        // unit-norm columns, so a dot product of two columns is their cosine
        double[] unit = new double[R.nnz()];
        for (int p = 0; p < R.nnz(); p++) {
            unit[p] = centred[p] / Math.max(Math.sqrt(squares[R.indices[p]]), 1e-9);
        }
        CsrMatrix unitRows = new CsrMatrix(R.nRows, R.nCols, R.indptr, R.indices, unit);
        CsrMatrix unitColumns = unitRows.transpose();

        int[][] neighbourIds = new int[nItems][];
        double[][] neighbourSims = new double[nItems][];
        IntStream.range(0, nItems).parallel().forEach(item -> {
            double[] sims = new double[nItems];
            int[] common = new int[nItems];
            for (int q = unitColumns.indptr[item]; q < unitColumns.indptr[item + 1]; q++) {
                int user = unitColumns.indices[q];
                double value = unitColumns.data[q];
                for (int p = unitRows.indptr[user]; p < unitRows.indptr[user + 1]; p++) {
                    sims[unitRows.indices[p]] += value * unitRows.data[p];
                    common[unitRows.indices[p]]++;
                }
            }
            for (int j = 0; j < nItems; j++) {
                sims[j] = common[j] == 0 || j == item ? 0.0 : Math.max(0.0, sims[j] * Cf.shrinkFactor(common[j], shrinkage));
            }
            int[] top = Arrays.stream(Num.topN(sims, Math.min(k, nItems - 1)))
                    .filter(j -> sims[j] > 0)
                    .sorted()
                    .toArray();
            neighbourIds[item] = top;
            neighbourSims[item] = Arrays.stream(top).mapToDouble(j -> sims[j]).toArray();
        });

        int[] indptr = new int[nItems + 1];
        for (int i = 0; i < nItems; i++) {
            indptr[i + 1] = indptr[i] + neighbourIds[i].length;
        }
        int[] indices = new int[indptr[nItems]];
        double[] data = new double[indptr[nItems]];
        for (int i = 0; i < nItems; i++) {
            System.arraycopy(neighbourIds[i], 0, indices, indptr[i], neighbourIds[i].length);
            System.arraycopy(neighbourSims[i], 0, data, indptr[i], neighbourSims[i].length);
        }
        return new CsrMatrix(nItems, nItems, indptr, indices, data);
    }

    // ----------------------------------------------------------------- scoring
    private ViewerProfile profile(Integer u, Map<Integer, Double> ratings) {
        double[] dev = new double[nItems];
        double[] mask = new double[nItems];
        double mean;
        if (ratings != null) {
            mean = ratings.isEmpty() ? muGlobal()
                    : Num.mean(ratings.values().stream().mapToDouble(Double::doubleValue).toArray());
            for (Map.Entry<Integer, Double> entry : ratings.entrySet()) {
                dev[entry.getKey()] = entry.getValue() - mean;
                mask[entry.getKey()] = 1.0;
            }
        } else {
            int lo = R.indptr[u];
            int hi = R.indptr[u + 1];
            mean = hi > lo ? Num.mean(Arrays.copyOfRange(R.data, lo, hi)) : muGlobal();
            for (int p = lo; p < hi; p++) {
                dev[R.indices[p]] = R.data[p] - mean;
                mask[R.indices[p]] = 1.0;
            }
        }
        return new ViewerProfile(mean, dev, mask);
    }

    private double muGlobal() {
        return R.nnz() > 0 ? (float) Num.mean(R.data) : 3.5;
    }

    /** Predicted rating for every item [0], plus the neighbour support behind it [1]. */
    public double[][] scoreAll(Integer u, Map<Integer, Double> ratings) {
        ViewerProfile viewer = profile(u, ratings);
        double[] numer = S.multiply(viewer.dev());
        double[] denom = S.multiply(viewer.mask());
        double[] preds = new double[nItems];
        for (int i = 0; i < nItems; i++) {
            double raw = denom[i] > 1e-9 ? viewer.mean() + numer[i] / Math.max(denom[i], 1e-9) : viewer.mean();
            preds[i] = Num.clip(raw, Cf.RATING_MIN, Cf.RATING_MAX);
        }
        return new double[][] {preds, denom};
    }

    /**
     * Top-N ranking score: the un-normalised similarity-weighted sum.
     *
     * <p>Ranking and rating prediction are different jobs. Dividing by the sum of similarities,
     * which rating prediction must do, lets an item supported by one weak neighbour reach 5.0 and
     * tie with genuinely strong picks. Keeping the raw sum keeps the strength of the evidence inside
     * the score. This is the classic top-N formulation of Deshpande &amp; Karypis (2004).
     */
    public double[] rankAll(Integer u, Map<Integer, Double> ratings) {
        return S.multiply(profile(u, ratings).dev());
    }

    /** The exact arithmetic behind one predicted rating. */
    public Map<String, Object> explain(int target, Integer u, Map<Integer, Double> ratings) {
        ViewerProfile viewer = profile(u, ratings);
        double mean = viewer.mean();
        List<Map<String, Object>> neighbours = new ArrayList<>();
        double numer = 0.0;
        double denom = 0.0;
        for (int p = S.indptr[target]; p < S.indptr[target + 1]; p++) {
            int neighbour = S.indices[p];
            if (viewer.mask()[neighbour] <= 0) {
                continue;
            }
            double sim = S.data[p];
            double dev = viewer.dev()[neighbour];
            numer += sim * dev;
            denom += Math.abs(sim);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("item_pos", neighbour);
            row.put("similarity", round(sim, 4));
            row.put("your_rating", round(dev + mean, 2));
            row.put("deviation", round(dev, 3));
            row.put("term", round(sim * dev, 4));
            neighbours.add(row);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        if (neighbours.isEmpty()) {
            out.put("neighbours", List.of());
            out.put("numerator", 0.0);
            out.put("denominator", 0.0);
            out.put("user_mean", round(mean, 3));
            out.put("k_used", 0);
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
        out.put("raw", round(raw, 4));
        return out;
    }

    public List<Cf.Rec> recommend(Integer u, Map<Integer, Double> ratings, int n) {
        double[] rank = rankAll(u, ratings);
        double[][] scored = scoreAll(u, ratings);
        return Cf.rankedList(rank, scored[0], scored[1], Cf.seenItems(R, u, ratings), n);
    }

    /** The n most similar items as {position, similarity}. */
    public List<double[]> similarItems(int itemPos, int n) {
        int lo = S.indptr[itemPos];
        double[] sims = Arrays.copyOfRange(S.data, lo, S.indptr[itemPos + 1]);
        List<double[]> out = new ArrayList<>();
        for (int o : Num.topN(sims, n)) {
            out.add(new double[] {S.indices[lo + o], sims[o]});
        }
        return out;
    }

    // ------------------------------------------------------------- persistence
    private static void writeMatrix(Path path, CsrMatrix m) throws IOException {
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(path)))) {
            out.writeInt(m.nRows);
            out.writeInt(m.nCols);
            out.writeInt(m.nnz());
            for (int v : m.indptr) {
                out.writeInt(v);
            }
            for (int v : m.indices) {
                out.writeInt(v);
            }
            for (double v : m.data) {
                out.writeDouble(v);
            }
        }
    }

    private static CsrMatrix readMatrix(Path path) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
            int nRows = in.readInt();
            int nCols = in.readInt();
            int nnz = in.readInt();
            int[] indptr = new int[nRows + 1];
            for (int i = 0; i <= nRows; i++) {
                indptr[i] = in.readInt();
            }
            int[] indices = new int[nnz];
            for (int i = 0; i < nnz; i++) {
                indices[i] = in.readInt();
            }
            double[] data = new double[nnz];
            for (int i = 0; i < nnz; i++) {
                data[i] = in.readDouble();
            }
            return new CsrMatrix(nRows, nCols, indptr, indices, data);
        }
    }
}
