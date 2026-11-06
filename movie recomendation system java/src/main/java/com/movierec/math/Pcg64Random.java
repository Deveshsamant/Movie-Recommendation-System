package com.movierec.math;

/**
 * numpy.random.default_rng(seed), ported: PCG64 seeded through SeedSequence, ziggurat normals and
 * NumPy's Fisher-Yates shuffle.
 *
 * <p>java.util.Random would train a statistically equivalent SVD, but with different numbers.
 * Reproducing NumPy's stream means seed 42 gives the same model the Python version gives.
 */
public final class Pcg64Random {

    private static final long MULT_HIGH = 2549297995355413924L;
    private static final long MULT_LOW = 4865540595714422341L;

    private long stateHigh;
    private long stateLow;
    private final long incHigh;
    private final long incLow;
    private boolean hasBufferedInt;
    private int bufferedInt;

    public Pcg64Random(long seed) {
        long[] words = seedSequence(seed);
        long seqHigh = words[2];
        long seqLow = words[3];
        incHigh = (seqHigh << 1) | (seqLow >>> 63);
        incLow = (seqLow << 1) | 1L;
        stateHigh = 0;
        stateLow = 0;
        step();
        long sum = stateLow + words[1];
        stateHigh = stateHigh + words[0] + (Long.compareUnsigned(sum, stateLow) < 0 ? 1 : 0);
        stateLow = sum;
        step();
    }

    // ---------------------------------------------------------------- SeedSequence
    private static final int INIT_A = 0x43b0d7e5;
    private static final int MULT_A = 0x931e8875;
    private static final int INIT_B = 0x8b51f9dd;
    private static final int MULT_B = 0x58f38ded;
    private static final int MIX_MULT_L = 0xca01f9dd;
    private static final int MIX_MULT_R = 0x4973f715;
    private static final int POOL_SIZE = 4;

    /** SeedSequence(seed).generate_state(4, uint64). */
    private static long[] seedSequence(long seed) {
        if (seed < 0) {
            throw new IllegalArgumentException("seed must be non-negative");
        }
        int[] entropy = (seed >>> 32) == 0
                ? new int[] {(int) seed}
                : new int[] {(int) seed, (int) (seed >>> 32)};

        int[] pool = new int[POOL_SIZE];
        int[] hash = {INIT_A};
        for (int i = 0; i < POOL_SIZE; i++) {
            pool[i] = hashMix(i < entropy.length ? entropy[i] : 0, hash);
        }
        for (int src = 0; src < POOL_SIZE; src++) {
            for (int dst = 0; dst < POOL_SIZE; dst++) {
                if (src != dst) {
                    pool[dst] = mix(pool[dst], hashMix(pool[src], hash));
                }
            }
        }

        int[] state = new int[8];
        int hashB = INIT_B;
        for (int i = 0; i < state.length; i++) {
            int value = pool[i % POOL_SIZE];
            value ^= hashB;
            hashB *= MULT_B;
            value *= hashB;
            value ^= value >>> 16;
            state[i] = value;
        }
        long[] out = new long[4];
        for (int i = 0; i < 4; i++) {
            out[i] = (state[2 * i] & 0xFFFFFFFFL) | ((long) state[2 * i + 1] << 32);
        }
        return out;
    }

    private static int hashMix(int value, int[] hash) {
        value ^= hash[0];
        hash[0] *= MULT_A;
        value *= hash[0];
        value ^= value >>> 16;
        return value;
    }

    private static int mix(int x, int y) {
        int result = MIX_MULT_L * x - MIX_MULT_R * y;
        result ^= result >>> 16;
        return result;
    }

    // ---------------------------------------------------------------- core generator
    private void step() {
        // 128-bit state * multiplier + increment, assembled from 64-bit pieces
        long low = stateLow * MULT_LOW;
        long high = unsignedMultiplyHigh(stateLow, MULT_LOW)
                + stateHigh * MULT_LOW
                + stateLow * MULT_HIGH;
        long sum = low + incLow;
        stateHigh = high + incHigh + (Long.compareUnsigned(sum, low) < 0 ? 1 : 0);
        stateLow = sum;
    }

    private static long unsignedMultiplyHigh(long a, long b) {
        return Math.multiplyHigh(a, b) + ((a >> 63) & b) + ((b >> 63) & a);
    }

    public long nextLong() {
        step();
        return Long.rotateRight(stateHigh ^ stateLow, (int) (stateHigh >>> 58));
    }

    /** Low half first, then the high half of the same 64-bit draw, like NumPy. */
    private int nextInt() {
        if (hasBufferedInt) {
            hasBufferedInt = false;
            return bufferedInt;
        }
        long next = nextLong();
        hasBufferedInt = true;
        bufferedInt = (int) (next >>> 32);
        return (int) next;
    }

    private double nextDouble() {
        return (nextLong() >>> 11) * (1.0 / 9007199254740992.0);
    }

    // ---------------------------------------------------------------- bounded integers
    /** A uniform integer in [0, max] by masked rejection, like NumPy's random_interval. */
    private int randomInterval(int max) {
        int mask = max;
        mask |= mask >>> 1;
        mask |= mask >>> 2;
        mask |= mask >>> 4;
        mask |= mask >>> 8;
        mask |= mask >>> 16;
        int value;
        do {
            value = nextInt() & mask;
        } while (value > max);
        return value;
    }

    /** rng.permutation(array): an in-place Fisher-Yates shuffle from the last position down. */
    public void shuffle(int[] values) {
        for (int i = values.length - 1; i >= 1; i--) {
            int j = randomInterval(i);
            int swap = values[j];
            values[j] = values[i];
            values[i] = swap;
        }
    }

    /** rng.permutation(n). */
    public int[] permutation(int n) {
        int[] values = new int[n];
        for (int i = 0; i < n; i++) {
            values[i] = i;
        }
        shuffle(values);
        return values;
    }

    // ---------------------------------------------------------------- normal distribution
    private static final double ZIG_R = 3.6541528853610087963519472518;
    private static final double ZIG_INV_R = 0.27366123732975827203338247596;
    private static final double ZIG_AREA = 0.004928673233974658;
    private static final double TWO_52 = 4503599627370496.0;
    private static final long[] KI = new long[256];
    private static final double[] WI = new double[256];
    private static final double[] FI = new double[256];

    static {
        // The 256 ziggurat layers, from the Marsaglia-Tsang recurrence. These agree with the
        // constants compiled into NumPy to about 4e-14.
        double[] x = new double[256];
        x[255] = ZIG_R;
        x[0] = ZIG_AREA / density(ZIG_R);
        for (int i = 254; i >= 1; i--) {
            x[i] = Math.sqrt(-2.0 * Math.log(ZIG_AREA / x[i + 1] + density(x[i + 1])));
        }
        for (int i = 0; i < 256; i++) {
            WI[i] = x[i] / TWO_52;
            FI[i] = i == 0 ? 1.0 : density(x[i]);
        }
        KI[0] = (long) (ZIG_R / x[0] * TWO_52);
        KI[1] = 0;
        for (int i = 2; i < 256; i++) {
            KI[i] = (long) (x[i - 1] / x[i] * TWO_52);
        }
    }

    private static double density(double x) {
        return Math.exp(-0.5 * x * x);
    }

    /** rng.standard_normal(). */
    public double nextStandardNormal() {
        while (true) {
            long r = nextLong();
            int idx = (int) (r & 0xff);
            r >>>= 8;
            boolean negative = (r & 1) != 0;
            long rabs = (r >>> 1) & 0x000fffffffffffffL;
            double x = rabs * WI[idx];
            if (negative) {
                x = -x;
            }
            if (rabs < KI[idx]) {
                return x;
            }
            if (idx == 0) {
                while (true) {
                    double xx = -ZIG_INV_R * Math.log1p(-nextDouble());
                    double yy = -Math.log1p(-nextDouble());
                    if (yy + yy > xx * xx) {
                        return ((rabs >>> 8) & 1) != 0 ? -(ZIG_R + xx) : ZIG_R + xx;
                    }
                }
            }
            if ((FI[idx - 1] - FI[idx]) * nextDouble() + FI[idx] < density(x)) {
                return x;
            }
        }
    }

    /** rng.normal(0, scale, size). */
    public double[] normal(double scale, int size) {
        double[] out = new double[size];
        for (int i = 0; i < size; i++) {
            out[i] = 0.0 + scale * nextStandardNormal();
        }
        return out;
    }
}
