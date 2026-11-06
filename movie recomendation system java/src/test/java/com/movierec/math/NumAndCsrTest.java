package com.movierec.math;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class NumAndCsrTest {

    @Test
    void roundsLikePython() {
        assertEquals(2.67, Num.round(2.675, 2)); // 2.675 is really 2.67499999...
        assertEquals(0.12, Num.round(0.125, 2)); // exact tie goes to the even digit
        assertEquals(0.38, Num.round(0.375, 2));
        assertEquals(3.0, Num.round(2.9999999, 4));
    }

    @Test
    void argsortIsDescendingAndKeepsTiesInIndexOrder() {
        assertArrayEquals(new int[] {3, 1, 4, 0, 2}, Num.argsortDescending(new double[] {1.0, 2.0, 0.5, 9.0, 2.0}));
        assertArrayEquals(new int[] {3, 1}, Num.topN(new double[] {1.0, 2.0, 0.5, 9.0, 2.0}, 2));
    }

    @Test
    void stdIsThePopulationStandardDeviation() {
        assertEquals(1.1180339887498949, Num.std(new double[] {1, 2, 3, 4}), 1e-15);
    }

    @Test
    void tripletsAreSortedByColumnAndDuplicatesSummed() {
        CsrMatrix m = CsrMatrix.fromTriplets(2, 4,
                new int[] {1, 0, 1, 0, 0},
                new int[] {2, 3, 0, 1, 3},
                new double[] {5, 1, 7, 2, 4});
        assertArrayEquals(new int[] {0, 2, 4}, m.indptr);
        assertArrayEquals(new int[] {1, 3, 0, 2}, m.indices);
        assertArrayEquals(new double[] {2, 5, 7, 5}, m.data);
    }

    @Test
    void multiplyGetAndTranspose() {
        CsrMatrix m = CsrMatrix.fromTriplets(2, 3, new int[] {0, 0, 1}, new int[] {0, 2, 1}, new double[] {1, 2, 3});
        assertArrayEquals(new double[] {7, 6}, m.multiply(new double[] {1, 2, 3}));
        assertEquals(2.0, m.get(0, 2));
        assertEquals(0.0, m.get(1, 2));
        CsrMatrix t = m.transpose();
        assertEquals(3, t.nRows);
        assertEquals(2.0, t.get(2, 0));
        assertArrayEquals(new int[] {1, 1, 1}, m.columnCounts());
        assertArrayEquals(new double[] {1.5, 3.0}, m.rowMeans());
    }
}
