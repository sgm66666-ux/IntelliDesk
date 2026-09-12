package com.intellidesk.benchmark;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Offline percentile calculator using the nearest-rank method (OPTION A authority).
 *
 * <p>k6 aggregate summary is only used as a cross-check; the persisted per-sample
 * raw is the authoritative input for percentile calculation.
 *
 * <p>Supported percentiles: p50, p90, p95, p99.
 */
public class BenchmarkPercentileCalculator {

    /**
     * Supported percentile ranks.
 */
    public enum Percentile {
        P50(50),
        P90(90),
        P95(95),
        P99(99);

        private final int rank;

        Percentile(int rank) {
            this.rank = rank;
        }

        public int getRank() {
            return rank;
        }
    }

    /**
     * Computes the requested percentiles from a list of latency samples.
     *
     * @param latencies latency values in milliseconds; must be non-empty
     * @return a map from percentile to latency value
     * @throws IllegalArgumentException if latencies is empty
     */
    public static java.util.Map<Percentile, Double> compute(List<Double> latencies) {
        if (latencies == null || latencies.isEmpty()) {
            throw new IllegalArgumentException("latencies must not be empty");
        }

        List<Double> sorted = new ArrayList<>(latencies);
        Collections.sort(sorted);
        int n = sorted.size();

        java.util.Map<Percentile, Double> result = new java.util.LinkedHashMap<>();
        for (Percentile p : Percentile.values()) {
            // Nearest-rank definition: ordinal = ceil(rank/100 * n)
            int ordinal = (int) Math.ceil(p.getRank() / 100.0 * n);
            int index = Math.min(Math.max(ordinal, 1), n) - 1; // convert to 0-based
            result.put(p, sorted.get(index));
        }
        return result;
    }

    /**
     * Convenience method that computes all supported percentiles from a double array.
     */
    public static java.util.Map<Percentile, Double> compute(double... latencies) {
        List<Double> list = new ArrayList<>(latencies.length);
        for (double d : latencies) {
            list.add(d);
        }
        return compute(list);
    }
}
