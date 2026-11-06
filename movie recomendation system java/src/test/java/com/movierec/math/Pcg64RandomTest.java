package com.movierec.math;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Reference values come from numpy.random.default_rng in NumPy 2.4. */
class Pcg64RandomTest {

    private static final double TOLERANCE = 1e-12;

    @Test
    void reproducesNumpyNormalsAndPermutationsInOneStream() {
        Pcg64Random rng = new Pcg64Random(42);

        double[] expected = {0.30471707975443135, -1.0399841062404955, 0.7504511958064572, 0.9405647163912139, -1.9510351886538364};
        for (double value : expected) {
            assertEquals(value, rng.nextStandardNormal(), TOLERANCE);
        }

        assertArrayEquals(new int[] {4, 8, 2, 6, 5, 9, 7, 3, 0, 1}, rng.permutation(10));

        // the half of a 32-bit draw left over from the shuffle must not disturb what follows
        double[] after = {1.1272412069680329, 0.4675093422520456, -0.8592924628832382};
        for (double value : after) {
            assertEquals(value, rng.nextStandardNormal(), TOLERANCE);
        }
    }

    @Test
    void reproducesTheFactorInitialisationAcrossThousandsOfDraws() {
        Pcg64Random rng = new Pcg64Random(42);
        double[] p = rng.normal(0.05, 610 * 50);
        double[] q = rng.normal(0.05, 9724 * 50);
        assertEquals(0.015235853987721568, p[0], TOLERANCE);
        assertEquals(-0.03322338411062425, p[p.length - 1], TOLERANCE);
        assertEquals(-0.03295374537276744, q[0], TOLERANCE);
        assertEquals(-0.021334461676641173, q[q.length - 1], TOLERANCE);
    }

    @Test
    void shufflesAnArrayLikeNumpyPermutation() {
        int[] pool = {10, 20, 30, 40, 50, 60, 70};
        new Pcg64Random(7).shuffle(pool);
        assertArrayEquals(new int[] {10, 60, 70, 30, 50, 20, 40}, pool);
    }

    @Test
    void acceptsSeedsWiderThan32Bits() {
        assertEquals(-8004693883070782951L, new Pcg64Random(123456789012L).nextLong());
    }
}
