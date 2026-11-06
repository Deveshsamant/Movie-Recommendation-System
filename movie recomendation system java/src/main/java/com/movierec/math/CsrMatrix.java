package com.movierec.math;

/** Compressed sparse row matrix: the Java stand-in for scipy.sparse.csr_matrix. */
public final class CsrMatrix {

    public final int nRows;
    public final int nCols;
    public final int[] indptr;
    public final int[] indices;
    public final double[] data;

    public CsrMatrix(int nRows, int nCols, int[] indptr, int[] indices, double[] data) {
        this.nRows = nRows;
        this.nCols = nCols;
        this.indptr = indptr;
        this.indices = indices;
        this.data = data;
    }

    /** Builds from (row, col, value) triplets; each row ends up sorted by column, duplicates summed. */
    public static CsrMatrix fromTriplets(int nRows, int nCols, int[] rows, int[] cols, double[] vals) {
        int n = rows.length;

        // Two stable counting passes (by column, then row) sort the triplets without comparing.
        int[] colStart = new int[nCols + 1];
        for (int c : cols) {
            colStart[c + 1]++;
        }
        for (int c = 0; c < nCols; c++) {
            colStart[c + 1] += colStart[c];
        }
        int[] byCol = new int[n];
        for (int t = 0; t < n; t++) {
            byCol[colStart[cols[t]]++] = t;
        }

        int[] rowStart = new int[nRows + 1];
        for (int r : rows) {
            rowStart[r + 1]++;
        }
        for (int r = 0; r < nRows; r++) {
            rowStart[r + 1] += rowStart[r];
        }
        int[] cursor = rowStart.clone();
        int[] sortedCols = new int[n];
        double[] sortedVals = new double[n];
        for (int t : byCol) {
            int at = cursor[rows[t]]++;
            sortedCols[at] = cols[t];
            sortedVals[at] = vals[t];
        }

        int[] indptr = new int[nRows + 1];
        int[] indices = new int[n];
        double[] data = new double[n];
        int out = 0;
        for (int r = 0; r < nRows; r++) {
            for (int p = rowStart[r]; p < rowStart[r + 1]; p++) {
                if (out > indptr[r] && indices[out - 1] == sortedCols[p]) {
                    data[out - 1] += sortedVals[p];
                } else {
                    indices[out] = sortedCols[p];
                    data[out] = sortedVals[p];
                    out++;
                }
            }
            indptr[r + 1] = out;
        }
        if (out == n) {
            return new CsrMatrix(nRows, nCols, indptr, indices, data);
        }
        return new CsrMatrix(nRows, nCols, indptr,
                java.util.Arrays.copyOf(indices, out), java.util.Arrays.copyOf(data, out));
    }

    public int nnz() {
        return indptr[nRows];
    }

    public int rowLength(int row) {
        return indptr[row + 1] - indptr[row];
    }

    /** y = A x. */
    public double[] multiply(double[] x) {
        double[] y = new double[nRows];
        for (int r = 0; r < nRows; r++) {
            double sum = 0.0;
            for (int p = indptr[r]; p < indptr[r + 1]; p++) {
                sum += data[p] * x[indices[p]];
            }
            y[r] = sum;
        }
        return y;
    }

    /** The dot product of one row with a dense vector. */
    public double rowDot(int row, double[] dense) {
        double sum = 0.0;
        for (int p = indptr[row]; p < indptr[row + 1]; p++) {
            sum += data[p] * dense[indices[p]];
        }
        return sum;
    }

    /** Value at (row, col), 0 when not stored. Rows are sorted, so this is a binary search. */
    public double get(int row, int col) {
        int at = java.util.Arrays.binarySearch(indices, indptr[row], indptr[row + 1], col);
        return at >= 0 ? data[at] : 0.0;
    }

    public CsrMatrix transpose() {
        int[] ptr = new int[nCols + 1];
        for (int p = 0; p < nnz(); p++) {
            ptr[indices[p] + 1]++;
        }
        for (int c = 0; c < nCols; c++) {
            ptr[c + 1] += ptr[c];
        }
        int[] cursor = ptr.clone();
        int[] idx = new int[nnz()];
        double[] val = new double[nnz()];
        for (int r = 0; r < nRows; r++) {
            for (int p = indptr[r]; p < indptr[r + 1]; p++) {
                int at = cursor[indices[p]]++;
                idx[at] = r;
                val[at] = data[p];
            }
        }
        return new CsrMatrix(nCols, nRows, ptr, idx, val);
    }

    /** The number of stored entries per column. */
    public int[] columnCounts() {
        int[] counts = new int[nCols];
        for (int p = 0; p < nnz(); p++) {
            counts[indices[p]]++;
        }
        return counts;
    }

    /** The mean of each row's stored values, 0 for an empty row. */
    public double[] rowMeans() {
        double[] means = new double[nRows];
        for (int r = 0; r < nRows; r++) {
            int count = rowLength(r);
            if (count > 0) {
                double sum = 0.0;
                for (int p = indptr[r]; p < indptr[r + 1]; p++) {
                    sum += data[p];
                }
                means[r] = sum / count;
            }
        }
        return means;
    }
}
