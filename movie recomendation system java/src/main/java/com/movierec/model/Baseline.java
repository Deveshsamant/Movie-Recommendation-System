package com.movierec.model;

import com.movierec.math.CsrMatrix;
import com.movierec.math.Num;

/**
 * r_hat = mu + b_u + b_i, fitted by alternating ridge updates.
 *
 * <p>Biases explain a surprising amount on their own: some users rate generously, some movies are
 * simply better liked. The other models learn the residual.
 */
public final class Baseline {

    private static final double REG_U = 10.0;
    private static final double REG_I = 12.0;
    private static final int ITERATIONS = 15;

    private final double mu;
    private final double[] bu;
    private final double[] bi;

    public Baseline(CsrMatrix R) {
        int nUsers = R.nRows;
        int nItems = R.nCols;
        int[] rows = new int[R.nnz()];
        for (int u = 0; u < nUsers; u++) {
            for (int p = R.indptr[u]; p < R.indptr[u + 1]; p++) {
                rows[p] = u;
            }
        }

        double sum = 0.0;
        for (double v : R.data) {
            sum += v;
        }
        // NumPy computes this on the float32 rating array, so mu and the first subtraction below
        // are single precision. Keeping the casts keeps the fitted biases identical.
        float muSingle = R.nnz() > 0 ? (float) (sum / R.nnz()) : 3.5f;
        this.mu = muSingle;

        int[] countU = new int[nUsers];
        int[] countI = new int[nItems];
        for (int p = 0; p < R.nnz(); p++) {
            countU[rows[p]]++;
            countI[R.indices[p]]++;
        }

        double[] userBias = new double[nUsers];
        double[] itemBias = new double[nItems];
        for (int iter = 0; iter < ITERATIONS; iter++) {
            itemBias = new double[nItems];
            for (int p = 0; p < R.nnz(); p++) {
                itemBias[R.indices[p]] += (double) ((float) R.data[p] - muSingle) - userBias[rows[p]];
            }
            for (int i = 0; i < nItems; i++) {
                itemBias[i] /= countI[i] + REG_I;
            }
            userBias = new double[nUsers];
            for (int p = 0; p < R.nnz(); p++) {
                userBias[rows[p]] += (double) ((float) R.data[p] - muSingle) - itemBias[R.indices[p]];
            }
            for (int u = 0; u < nUsers; u++) {
                userBias[u] /= countU[u] + REG_U;
            }
        }
        this.bu = userBias;
        this.bi = itemBias;
    }

    public double[] scoreAll(int u) {
        double[] out = rankAll(u);
        for (int i = 0; i < out.length; i++) {
            out[i] = Num.clip(out[i], Cf.RATING_MIN, Cf.RATING_MAX);
        }
        return out;
    }

    public double[] rankAll(int u) {
        double[] out = new double[bi.length];
        for (int i = 0; i < out.length; i++) {
            out[i] = mu + bu[u] + bi[i];
        }
        return out;
    }
}
