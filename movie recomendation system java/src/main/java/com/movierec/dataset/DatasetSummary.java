package com.movierec.dataset;

import static com.movierec.math.Num.round;

import com.movierec.math.CsrMatrix;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Descriptive statistics about the ratings, for the charts in the guided overview. */
public final class DatasetSummary {

    private DatasetSummary() {}

    public static Map<String, Object> of(MovieData data) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rating_histogram", ratingHistogram(data));
        out.put("genres", genres(data));
        out.put("decades", decades(data));
        out.put("long_tail", longTail(data));
        out.put("viewers", viewers(data));
        out.put("sparsity", sparsity(data));
        out.put("split", split(data));
        return out;
    }

    private static List<Map<String, Object>> ratingHistogram(MovieData data) {
        int[] buckets = new int[10];
        for (double rating : data.ratingMatrix.data) {
            buckets[Math.min(9, Math.max(0, (int) Math.rint(rating * 2) - 1))]++;
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < buckets.length; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("rating", (i + 1) * 0.5);
            row.put("count", buckets[i]);
            rows.add(row);
        }
        return rows;
    }

    /** A film with several genres counts once in each, so these rows do not add up to the total. */
    private static List<Map<String, Object>> genres(MovieData data) {
        Map<String, long[]> counts = new TreeMap<>();
        Map<String, Double> ratingSums = new TreeMap<>();
        for (Movie movie : data.movies) {
            for (String genre : movie.genres()) {
                long[] tally = counts.computeIfAbsent(genre, key -> new long[2]);
                tally[0]++;
                tally[1] += movie.nRatings();
                ratingSums.merge(genre, movie.avgRating() * movie.nRatings(), Double::sum);
            }
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        counts.forEach((genre, tally) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("genre", genre);
            row.put("films", tally[0]);
            row.put("ratings", tally[1]);
            row.put("avg_rating", tally[1] == 0 ? 0.0 : round(ratingSums.get(genre) / tally[1], 2));
            rows.add(row);
        });
        rows.sort((a, b) -> Long.compare((long) b.get("ratings"), (long) a.get("ratings")));
        return rows;
    }

    private static List<Map<String, Object>> decades(MovieData data) {
        Map<Integer, long[]> counts = new TreeMap<>();
        for (Movie movie : data.movies) {
            if (movie.year() != null) {
                long[] tally = counts.computeIfAbsent(movie.year() / 10 * 10, key -> new long[2]);
                tally[0]++;
                tally[1] += movie.nRatings();
            }
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        counts.forEach((decade, tally) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("decade", decade);
            row.put("films", tally[0]);
            row.put("ratings", tally[1]);
            rows.add(row);
        });
        return rows;
    }

    /** How concentrated the ratings are on a few popular films. */
    private static Map<String, Object> longTail(MovieData data) {
        int nItems = data.nItems();
        int[] counts = new int[nItems];
        for (int i = 0; i < nItems; i++) {
            counts[i] = data.movies[i].nRatings();
        }
        Arrays.sort(counts); // ascending: most-rated films are read from the end

        long total = 0;
        int oneRating = 0;
        int underFive = 0;
        for (int count : counts) {
            total += count;
            if (count == 1) {
                oneRating++;
            }
            if (count < 5) {
                underFive++;
            }
        }

        long[] cumulative = new long[nItems + 1];
        for (int i = 0; i < nItems; i++) {
            cumulative[i + 1] = cumulative[i] + counts[nItems - 1 - i];
        }

        List<Map<String, Object>> curve = new ArrayList<>();
        for (int pct = 0; pct <= 100; pct += 2) {
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("films_pct", pct);
            point.put("ratings_pct", share(cumulative, pct, total));
            curve.add(point);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("curve", curve);
        out.put("top_1_pct_share", share(cumulative, 1, total));
        out.put("top_10_pct_share", share(cumulative, 10, total));
        out.put("top_20_pct_share", share(cumulative, 20, total));
        out.put("films_with_one_rating", oneRating);
        out.put("films_under_five_ratings", underFive);
        out.put("median_ratings_per_film", nItems == 0 ? 0 : counts[nItems / 2]);
        out.put("most_ratings_on_a_film", nItems == 0 ? 0 : counts[nItems - 1]);
        return out;
    }

    private static double share(long[] cumulative, int filmsPct, long total) {
        int films = (int) Math.round((cumulative.length - 1) * (filmsPct / 100.0));
        return total == 0 ? 0.0 : round(cumulative[films] * 100.0 / total, 1);
    }

    private static Map<String, Object> viewers(MovieData data) {
        CsrMatrix R = data.ratingMatrix;
        int[] counts = new int[R.nRows];
        long total = 0;
        for (int u = 0; u < R.nRows; u++) {
            counts[u] = R.rowLength(u);
            total += counts[u];
        }
        Arrays.sort(counts);
        Map<String, Object> out = new LinkedHashMap<>();
        boolean empty = counts.length == 0;
        out.put("min_ratings", empty ? 0 : counts[0]);
        out.put("median_ratings", empty ? 0 : counts[counts.length / 2]);
        out.put("mean_ratings", empty ? 0.0 : round(total / (double) counts.length, 1));
        out.put("max_ratings", empty ? 0 : counts[counts.length - 1]);
        return out;
    }

    private static Map<String, Object> sparsity(MovieData data) {
        long cells = (long) data.nUsers() * data.nItems();
        int filled = data.ratingMatrix.nnz();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total_cells", cells);
        out.put("filled_cells", filled);
        out.put("filled_pct", cells == 0 ? 0.0 : round(filled * 100.0 / cells, 2));
        out.put("empty_pct", cells == 0 ? 0.0 : round(100.0 - filled * 100.0 / cells, 2));
        return out;
    }

    private static Map<String, Object> split(MovieData data) {
        int test = data.testByUser.values().stream().mapToInt(Map::size).sum();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("train_ratings", data.train.nnz());
        out.put("test_ratings", test);
        return out;
    }
}
