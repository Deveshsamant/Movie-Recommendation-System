package com.movierec.math;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;

/** Small numeric helpers: the parts of numpy and Python's round() this project relies on. */
public final class Num {

    private Num() {}

    /**
     * Same result as Python's round(x, digits): the exact binary value rounded half-even.
     * Math.round would turn 2.675 into 2.68, where Python gives 2.67.
     */
    public static double round(double x, int digits) {
        if (Double.isNaN(x) || Double.isInfinite(x)) {
            return x;
        }
        return new BigDecimal(x).setScale(digits, RoundingMode.HALF_EVEN).doubleValue();
    }

    public static double clip(double x, double lo, double hi) {
        return Math.max(lo, Math.min(hi, x));
    }

    public static double mean(double[] values) {
        if (values.length == 0) {
            return 0.0;
        }
        double sum = 0.0;
        for (double v : values) {
            sum += v;
        }
        return sum / values.length;
    }

    /** Population standard deviation, like np.std. */
    public static double std(double[] values) {
        if (values.length == 0) {
            return 0.0;
        }
        double mean = mean(values);
        double sum = 0.0;
        for (double v : values) {
            sum += (v - mean) * (v - mean);
        }
        return Math.sqrt(sum / values.length);
    }

    public static double norm(double[] values) {
        double sum = 0.0;
        for (double v : values) {
            sum += v * v;
        }
        return Math.sqrt(sum);
    }

    /** Indices that sort values from largest to smallest; ties keep index order. */
    public static int[] argsortDescending(double[] values) {
        int n = values.length;
        int[] order = new int[n];
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }
        // Bottom-up merge sort over primitives: this runs thousands of times during evaluation.
        int[] buffer = new int[n];
        for (int width = 1; width < n; width *= 2) {
            for (int lo = 0; lo < n; lo += 2 * width) {
                int mid = Math.min(lo + width, n);
                int hi = Math.min(lo + 2 * width, n);
                int left = lo;
                int right = mid;
                int out = lo;
                while (left < mid && right < hi) {
                    buffer[out++] = values[order[right]] > values[order[left]] ? order[right++] : order[left++];
                }
                while (left < mid) {
                    buffer[out++] = order[left++];
                }
                while (right < hi) {
                    buffer[out++] = order[right++];
                }
            }
            int[] swap = order;
            order = buffer;
            buffer = swap;
        }
        return order;
    }

    public static int[] argsortDescending(int[] values) {
        double[] asDouble = new double[values.length];
        for (int i = 0; i < values.length; i++) {
            asDouble[i] = values[i];
        }
        return argsortDescending(asDouble);
    }

    /** The first n positions of the descending order. */
    public static int[] topN(double[] values, int n) {
        int[] order = argsortDescending(values);
        return n >= order.length ? order : Arrays.copyOf(order, Math.max(0, n));
    }
}
