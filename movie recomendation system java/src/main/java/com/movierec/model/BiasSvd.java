package com.movierec.model;

import static com.movierec.math.Num.round;

import com.movierec.math.CsrMatrix;
import com.movierec.math.Num;
import com.movierec.math.Pcg64Random;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.commons.math3.linear.Array2DRowRealMatrix;
import org.apache.commons.math3.linear.ArrayRealVector;
import org.apache.commons.math3.linear.DecompositionSolver;
import org.apache.commons.math3.linear.LUDecomposition;

/**
 * r_hat = mu + b_u + b_i + p_u . q_i, trained by mini-batch SGD (Funk / Koren 2009).
 *
 * <p>The model family that won the Netflix Prize. Every user and movie gets a vector of latent
 * factors discovered from the ratings alone; their dot product measures how well the movie's mix
 * lines up with the user's taste. Training minimises squared error with L2 regularisation on all
 * learned parameters.
 */
public final class BiasSvd {

    private static final int BATCH = 4096;

    /** A viewer in factor space: their p vector and user bias. */
    public record Factors(double[] p, double b) {}

    public final int nFactors;
    public final int nEpochs;
    public final double lr;
    public final double reg;
    public final List<Map<String, Object>> history;

    private final CsrMatrix R;
    private final int nUsers;
    private final int nItems;
    private double mu;
    private double[] bu;
    private double[] bi;
    /** Factor matrices, row-major: P[u * nFactors + f], Q[i * nFactors + f]. */
    private double[] P;
    private double[] Q;

    private BiasSvd(CsrMatrix R, int nFactors, int nEpochs, double lr, double reg) {
        this.R = R;
        this.nUsers = R.nRows;
        this.nItems = R.nCols;
        this.nFactors = nFactors;
        this.nEpochs = nEpochs;
        this.lr = lr;
        this.reg = reg;
        this.history = new ArrayList<>();
    }

    /** Trains once, reuses afterwards: keyed by matrix shape and hyper-parameters. */
    public static BiasSvd cached(CsrMatrix R, Map<String, Object> params, String tag, boolean verbose, Path cacheDir) {
        int nFactors = ((Number) params.get("n_factors")).intValue();
        int nEpochs = ((Number) params.get("n_epochs")).intValue();
        double lr = ((Number) params.get("lr")).doubleValue();
        double reg = ((Number) params.get("reg")).doubleValue();
        long seed = ((Number) params.get("seed")).longValue();
        Path path = cacheDir.resolve(String.format("svd_%s_%d_%d_%d_%s_%s.bin",
                tag, R.nRows, R.nCols, R.nnz(), nFactors, nEpochs));

        BiasSvd model = new BiasSvd(R, nFactors, nEpochs, lr, reg);
        if (Files.exists(path)) {
            try {
                model.read(path);
                return model;
            } catch (IOException e) {
                System.err.println("retraining SVD, cache unreadable: " + e.getMessage());
            }
        }
        model.fit(seed, verbose);
        try {
            model.write(path);
        } catch (IOException e) {
            System.err.println("could not cache SVD: " + e.getMessage());
        }
        return model;
    }

    private void fit(long seed, boolean verbose) {
        Pcg64Random rng = new Pcg64Random(seed);
        int n = R.nnz();
        int[] rows = new int[n];
        for (int u = 0; u < nUsers; u++) {
            for (int p = R.indptr[u]; p < R.indptr[u + 1]; p++) {
                rows[p] = u;
            }
        }
        int[] cols = R.indices;
        double[] vals = R.data;

        mu = Num.mean(vals);
        bu = new double[nUsers];
        bi = new double[nItems];
        P = rng.normal(0.05, nUsers * nFactors);
        Q = rng.normal(0.05, nItems * nFactors);

        // A mini-batch computes every update from the parameters as they stood when the batch
        // began, so the values used are copied first and only then applied.
        int F = nFactors;
        double[] err = new double[BATCH];
        double[] buStart = new double[BATCH];
        double[] biStart = new double[BATCH];
        double[] puStart = new double[BATCH * F];
        double[] qiStart = new double[BATCH * F];

        for (int epoch = 0; epoch < nEpochs; epoch++) {
            int[] order = rng.permutation(n);
            double step = lr / (1.0 + 0.03 * epoch); // decay: refine, do not oscillate
            double sqErr = 0.0;
            for (int start = 0; start < n; start += BATCH) {
                int size = Math.min(BATCH, n - start);
                for (int j = 0; j < size; j++) {
                    int t = order[start + j];
                    int u = rows[t];
                    int i = cols[t];
                    System.arraycopy(P, u * F, puStart, j * F, F);
                    System.arraycopy(Q, i * F, qiStart, j * F, F);
                    buStart[j] = bu[u];
                    biStart[j] = bi[i];
                    double dot = 0.0;
                    for (int f = 0; f < F; f++) {
                        dot += puStart[j * F + f] * qiStart[j * F + f];
                    }
                    err[j] = vals[t] - (mu + buStart[j] + biStart[j] + dot);
                    sqErr += err[j] * err[j];
                }
                for (int j = 0; j < size; j++) {
                    int t = order[start + j];
                    int u = rows[t];
                    int i = cols[t];
                    double e = err[j];
                    bu[u] += step * (e - reg * buStart[j]);
                    bi[i] += step * (e - reg * biStart[j]);
                    for (int f = 0; f < F; f++) {
                        double pu = puStart[j * F + f];
                        double qi = qiStart[j * F + f];
                        P[u * F + f] += step * (e * qi - reg * pu);
                        Q[i * F + f] += step * (e * pu - reg * qi);
                    }
                }
            }
            double rmse = round(Math.sqrt(sqErr / n), 4);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("epoch", epoch + 1);
            row.put("train_rmse", rmse);
            history.add(row);
            if (verbose) {
                System.out.printf("  epoch %2d  train RMSE %.4f%n", epoch + 1, rmse);
            }
        }
    }

    // --------------------------------------------------------------- accessors
    public double mu() {
        return mu;
    }

    public double[] itemFactors() {
        return Q;
    }

    public double itemFactor(int item, int f) {
        return Q[item * nFactors + f];
    }

    public Factors userFactors(int u) {
        double[] p = new double[nFactors];
        System.arraycopy(P, u * nFactors, p, 0, nFactors);
        return new Factors(p, bu[u]);
    }

    private double dotWithItem(double[] p, int item) {
        double sum = 0.0;
        int at = item * nFactors;
        for (int f = 0; f < nFactors; f++) {
            sum += p[f] * Q[at + f];
        }
        return sum;
    }

    // --------------------------------------------------------------- inference
    /** The exact arithmetic behind one predicted rating. */
    public Map<String, Object> explain(int target, Factors viewer) {
        double[] p = viewer.p();
        double dot = dotWithItem(p, target);
        double raw = mu + viewer.b() + bi[target] + dot;
        double pred = Num.clip(raw, Cf.RATING_MIN, Cf.RATING_MAX);

        double[] contributions = new double[nFactors];
        double[] magnitude = new double[nFactors];
        for (int f = 0; f < nFactors; f++) {
            contributions[f] = p[f] * itemFactor(target, f);
            magnitude[f] = Math.abs(contributions[f]);
        }
        List<Map<String, Object>> factors = new ArrayList<>();
        for (int f : Num.topN(magnitude, 8)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("k", f);
            row.put("p_u", round(p[f], 4));
            row.put("q_i", round(itemFactor(target, f), 4));
            row.put("product", round(contributions[f], 4));
            factors.add(row);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mu", round(mu, 4));
        out.put("b_u", round(viewer.b(), 4));
        out.put("b_i", round(bi[target], 4));
        out.put("dot", round(dot, 4));
        out.put("raw", round(raw, 4));
        out.put("clipped", Math.abs(raw - pred) > 1e-9);
        out.put("n_factors", nFactors);
        out.put("factors", factors);
        return out;
    }

    public double[] scoreAll(Factors viewer) {
        double[] out = rankAll(viewer);
        for (int i = 0; i < nItems; i++) {
            out[i] = Num.clip(out[i], Cf.RATING_MIN, Cf.RATING_MAX);
        }
        return out;
    }

    /**
     * Unclipped score. Clipping at 5.0 creates hundreds of exact ties, and a tie is resolved by
     * array order, which is not a ranking at all.
     */
    public double[] rankAll(Factors viewer) {
        double[] out = new double[nItems];
        double base = mu + viewer.b();
        for (int i = 0; i < nItems; i++) {
            out[i] = base + bi[i] + dotWithItem(viewer.p(), i);
        }
        return out;
    }

    public List<Cf.Rec> recommend(Factors viewer, int n, Set<Integer> seen) {
        return Cf.rankedList(rankAll(viewer), scoreAll(viewer), null, seen, n);
    }

    public Set<Integer> seenBy(int u) {
        return Cf.seenItems(R, u, null);
    }

    // ----------------------------------------------------------------- fold-in
    /**
     * Learns p_u and b_u for a brand-new user without retraining.
     *
     * <p>Item factors stay frozen, so this is a small ridge regression solved in closed form and
     * alternated with the user-bias update:
     *
     * <pre>p = (Qr' Qr + lambda I)^-1  Qr' e,   e = r - mu - b_u - b_i</pre>
     */
    public Factors foldIn(Map<Integer, Double> ratings) {
        int n = ratings.size();
        int F = nFactors;
        if (n == 0) {
            return new Factors(new double[F], 0.0);
        }
        int[] items = new int[n];
        double[] vals = new double[n];
        int at = 0;
        for (Map.Entry<Integer, Double> entry : ratings.entrySet()) {
            items[at] = entry.getKey();
            vals[at] = entry.getValue();
            at++;
        }

        double ridge = Math.max(reg, 0.02) * n;
        double[][] A = new double[F][F];
        for (int item : items) {
            int offset = item * F;
            for (int a = 0; a < F; a++) {
                for (int b = 0; b < F; b++) {
                    A[a][b] += Q[offset + a] * Q[offset + b];
                }
            }
        }
        for (int f = 0; f < F; f++) {
            A[f][f] += ridge;
        }
        DecompositionSolver solver = new LUDecomposition(new Array2DRowRealMatrix(A, false)).getSolver();

        double[] p = new double[F];
        double b = 0.0;
        for (int iter = 0; iter < 8; iter++) {
            double residual = 0.0;
            for (int r = 0; r < n; r++) {
                residual += vals[r] - mu - bi[items[r]] - dotWithItem(p, items[r]);
            }
            b = residual / n;
            double[] rhs = new double[F];
            for (int r = 0; r < n; r++) {
                double e = vals[r] - mu - b - bi[items[r]];
                int offset = items[r] * F;
                for (int f = 0; f < F; f++) {
                    rhs[f] += Q[offset + f] * e;
                }
            }
            p = solver.solve(new ArrayRealVector(rhs, false)).toArray();
        }
        return new Factors(p, b);
    }

    // ------------------------------------------------------------- persistence
    private void write(Path path) throws IOException {
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(path)))) {
            out.writeDouble(mu);
            for (double[] array : new double[][] {bu, bi, P, Q}) {
                for (double v : array) {
                    out.writeDouble(v);
                }
            }
            out.writeInt(history.size());
            for (Map<String, Object> row : history) {
                out.writeDouble((double) row.get("train_rmse"));
            }
        }
    }

    private void read(Path path) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
            mu = in.readDouble();
            bu = readArray(in, nUsers);
            bi = readArray(in, nItems);
            P = readArray(in, nUsers * nFactors);
            Q = readArray(in, nItems * nFactors);
            int epochs = in.readInt();
            history.clear();
            for (int e = 0; e < epochs; e++) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("epoch", e + 1);
                row.put("train_rmse", in.readDouble());
                history.add(row);
            }
        }
    }

    private static double[] readArray(DataInputStream in, int size) throws IOException {
        double[] out = new double[size];
        for (int i = 0; i < size; i++) {
            out[i] = in.readDouble();
        }
        return out;
    }
}
