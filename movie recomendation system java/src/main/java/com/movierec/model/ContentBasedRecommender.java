package com.movierec.model;

import static com.movierec.math.Num.round;

import com.movierec.dataset.Movie;
import com.movierec.dataset.MovieData;
import com.movierec.math.CsrMatrix;
import com.movierec.math.Num;
import com.movierec.tmdb.TmdbClient;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Content-based filtering: TF-IDF over movie metadata + cosine similarity.
 *
 * <p>The model answers "what is this movie about, and what else is about the same thing" using only
 * item metadata: it never looks at what other users did. Every score it returns is accompanied by
 * an exact decomposition, because cosine similarity of L2-normalised vectors is just a dot product:
 *
 * <pre>sim(a, b) = sum_t  tfidf_a[t] * tfidf_b[t]</pre>
 *
 * so each shared term's contribution is a real number that sums to the score.
 */
public final class ContentBasedRecommender {

    // Repetition is how you weight a term inside a bag-of-words document.
    private static final int GENRE_WEIGHT = 3;
    private static final int TAG_WEIGHT = 2;
    private static final int KEYWORD_WEIGHT = 2;
    private static final int DIRECTOR_WEIGHT = 2;
    private static final int CAST_WEIGHT = 1;

    private static final Pattern NOT_ALPHANUMERIC = Pattern.compile("[^a-z0-9]+");
    private static final Pattern EDGE_UNDERSCORES = Pattern.compile("^_+|_+$");

    private static final Map<String, String> TERM_KINDS = Map.of(
            "g", "genre", "t", "tag", "k", "keyword", "d", "director",
            "c", "cast", "w", "title word", "decade", "decade");

    public record Component(int movieId, double rating, double weight) {}

    /** A taste vector. vector is null when none of the viewer's films are in the catalogue. */
    public record Profile(double[] vector, List<Component> components, double mean, double norm) {}

    public record Scored(int movieId, double score) {}

    public record Recommendation(List<Scored> items, List<Map<String, Object>> profileTerms, Profile profile) {}

    private final MovieData data;
    private final CsrMatrix vectors;
    private final String[] terms;
    private final int enriched;

    public ContentBasedRecommender(MovieData data, TmdbClient tmdb) {
        this.data = data;
        List<String> documents = new ArrayList<>(data.nItems());
        int enrichedCount = 0;
        for (Movie movie : data.movies) {
            Map<String, Object> meta = tmdb.cachedOnly(movie.tmdbId());
            if (meta != null) {
                enrichedCount++;
            }
            documents.add(document(movie, meta));
        }
        this.enriched = enrichedCount;
        TfidfModel model = TfidfModel.fit(documents);
        this.vectors = model.matrix;
        this.terms = model.terms;
    }

    public CsrMatrix vectors() {
        return vectors;
    }

    // --------------------------------------------------------------- documents
    static String slug(String text) {
        String joined = NOT_ALPHANUMERIC.matcher(text.toLowerCase(Locale.ROOT).strip()).replaceAll("_");
        return EDGE_UNDERSCORES.matcher(joined).replaceAll("");
    }

    private static void addRepeated(List<String> tokens, String token, int times) {
        for (int i = 0; i < times; i++) {
            tokens.add(token);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> names(Map<String, Object> meta, String key) {
        Object value = meta.get(key);
        return value instanceof List ? (List<String>) value : List.of();
    }

    private static String document(Movie movie, Map<String, Object> meta) {
        List<String> tokens = new ArrayList<>();
        for (String genre : movie.genres()) {
            addRepeated(tokens, "g_" + slug(genre), GENRE_WEIGHT);
        }
        for (String tag : movie.tags()) {
            addRepeated(tokens, "t_" + slug(tag), TAG_WEIGHT);
        }
        if (meta != null) {
            for (String keyword : names(meta, "keywords")) {
                addRepeated(tokens, "k_" + slug(keyword), KEYWORD_WEIGHT);
            }
            for (String director : names(meta, "directors")) {
                addRepeated(tokens, "d_" + slug(director), DIRECTOR_WEIGHT);
            }
            List<String> cast = names(meta, "cast");
            for (String actor : cast.subList(0, Math.min(6, cast.size()))) {
                addRepeated(tokens, "c_" + slug(actor), CAST_WEIGHT);
            }
        }
        if (movie.year() != null && movie.year() != 0) {
            tokens.add("decade_" + movie.year() / 10 * 10 + "s");
        }
        for (String word : slug(movie.cleanTitle()).split("_")) {
            if (word.length() > 3) {
                tokens.add("w_" + word);
            }
        }
        return tokens.isEmpty() ? "unknown" : String.join(" ", tokens);
    }

    // --------------------------------------------------------------- utilities
    private Integer position(int movieId) {
        return data.itemPos.get(movieId);
    }

    /** The label and kind shown for a raw vocabulary term such as "d_christopher_nolan". */
    private static String[] pretty(String term) {
        int split = term.indexOf('_');
        String prefix = split >= 0 ? term.substring(0, split) : term;
        String rest = split >= 0 ? term.substring(split + 1) : "";
        if (term.startsWith("decade_")) {
            return new String[] {rest, "decade"};
        }
        return new String[] {rest.replace('_', ' '), TERM_KINDS.getOrDefault(prefix, "term")};
    }

    /** A row of the matrix as a dense array over the whole vocabulary. */
    private double[] dense(int row) {
        double[] out = new double[vectors.nCols];
        for (int p = vectors.indptr[row]; p < vectors.indptr[row + 1]; p++) {
            out[vectors.indices[p]] = vectors.data[p];
        }
        return out;
    }

    /** Exact per-term breakdown of cosine(a, b). */
    public Map<String, Object> explainPair(int movieA, int movieB, int topN) {
        int rowA = position(movieA);
        double[] b = dense(position(movieB));

        int lo = vectors.indptr[rowA];
        int count = vectors.rowLength(rowA);
        double[] products = new double[count];
        double total = 0.0;
        for (int i = 0; i < count; i++) {
            products[i] = vectors.data[lo + i] * b[vectors.indices[lo + i]];
            total += products[i];
        }

        List<Map<String, Object>> parts = new ArrayList<>();
        double covered = 0.0;
        for (int i : Num.topN(products, topN)) {
            if (products[i] <= 0) {
                break;
            }
            int term = vectors.indices[lo + i];
            String[] info = pretty(terms[term]);
            double contribution = round(products[i], 4);
            Map<String, Object> part = new LinkedHashMap<>();
            part.put("term", info[0]);
            part.put("kind", info[1]);
            part.put("weight_a", round(vectors.data[lo + i], 4));
            part.put("weight_b", round(b[term], 4));
            part.put("contribution", contribution);
            parts.add(part);
            covered += contribution;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("score", round(total, 4));
        out.put("terms", parts);
        out.put("explained_share", total > 0 ? round(covered / total, 3) : 0.0);
        return out;
    }

    // ------------------------------------------------------------ item -> item
    public List<Scored> similarToMovie(int movieId, int n) {
        Integer row = position(movieId);
        if (row == null) {
            return List.of();
        }
        double[] sims = vectors.multiply(dense(row));
        sims[row] = -1.0;
        List<Scored> out = new ArrayList<>();
        for (int i : Num.topN(sims, n)) {
            if (sims[i] > 0) {
                out.add(new Scored(data.itemIds[i], sims[i]));
            }
        }
        return out;
    }

    // ------------------------------------------------------------ user profile
    /**
     * Rocchio profile: a taste vector built from the user's own ratings.
     *
     * <p>Items rated above the user's personal mean pull the profile toward them, items below it
     * push away.
     */
    public Profile buildProfile(Map<Integer, Double> rated) {
        List<Integer> movieIds = new ArrayList<>();
        List<Double> ratings = new ArrayList<>();
        rated.forEach((movieId, rating) -> {
            if (position(movieId) != null) {
                movieIds.add(movieId);
                ratings.add(rating);
            }
        });
        if (movieIds.isEmpty()) {
            return new Profile(null, List.of(), 0.0, 0.0);
        }

        double[] values = ratings.stream().mapToDouble(Double::doubleValue).toArray();
        double mean = Num.mean(values);
        // A user who rated everything identically gets a positive-only profile.
        double spread = Math.max(1e-6, Num.std(values));

        List<Component> components = new ArrayList<>(movieIds.size());
        double[] profile = new double[vectors.nCols];
        for (int i = 0; i < values.length; i++) {
            double weight = spread > 1e-6 ? (values[i] - mean) / spread : 1.0;
            if (Math.abs(weight) < 1e-9) {
                weight = 0.15; // neutral ratings still carry a little signal
            }
            int row = position(movieIds.get(i));
            for (int p = vectors.indptr[row]; p < vectors.indptr[row + 1]; p++) {
                profile[vectors.indices[p]] += vectors.data[p] * weight;
            }
            components.add(new Component(movieIds.get(i), values[i], weight));
        }
        double norm = Num.norm(profile);
        if (norm > 0) {
            for (int t = 0; t < profile.length; t++) {
                profile[t] /= norm;
            }
        }
        return new Profile(profile, components, mean, norm);
    }

    public List<Map<String, Object>> topProfileTerms(Profile profile, int topN) {
        if (profile.vector() == null) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (int t : Num.topN(profile.vector(), topN)) {
            if (profile.vector()[t] <= 0) {
                break;
            }
            String[] info = pretty(terms[t]);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("term", info[0]);
            row.put("kind", info[1]);
            row.put("weight", round(profile.vector()[t], 4));
            out.add(row);
        }
        return out;
    }

    /** Scores every unseen movie against the user's taste vector. */
    public Recommendation recommend(Map<Integer, Double> rated, int n, Profile given) {
        Profile profile = given != null ? given : buildProfile(rated);
        if (profile.vector() == null) {
            return new Recommendation(List.of(), List.of(), profile);
        }
        double[] scores = vectors.multiply(profile.vector()); // cosine: both sides are L2-normalised
        for (int movieId : rated.keySet()) {
            Integer row = position(movieId);
            if (row != null) {
                scores[row] = -1.0;
            }
        }
        List<Scored> items = new ArrayList<>();
        for (int i : Num.topN(scores, n * 3)) {
            if (scores[i] <= 0) {
                continue;
            }
            items.add(new Scored(data.itemIds[i], scores[i]));
            if (items.size() >= n) {
                break;
            }
        }
        return new Recommendation(items, topProfileTerms(profile, 12), profile);
    }

    public Recommendation recommend(Map<Integer, Double> rated, int n) {
        return recommend(rated, n, null);
    }

    /** Every item's cosine against the profile, without hiding seen films. */
    public double[] scoreAll(Profile profile) {
        return profile.vector() == null ? new double[data.nItems()] : vectors.multiply(profile.vector());
    }

    /**
     * Attributes one recommendation back to the movies that caused it.
     *
     * <p>profile = (1/||p||) * sum_i w_i * v_i, so score(j) = sum_i (w_i / ||p||) * (v_i . v_j): an
     * exact split.
     */
    public Map<String, Object> explainRecommendation(Profile profile, int movieId, int topN) {
        Integer targetRow = position(movieId);
        Map<String, Object> out = new LinkedHashMap<>();
        if (targetRow == null || profile.vector() == null) {
            out.put("score", 0.0);
            out.put("because_of", List.of());
            out.put("terms", List.of());
            return out;
        }

        double[] target = dense(targetRow);
        double norm = profile.norm() != 0 ? profile.norm() : 1.0;
        List<Component> components = profile.components();
        double[] sims = new double[components.size()];
        double[] contribs = new double[components.size()];
        int nSources = 0;
        for (int i = 0; i < sims.length; i++) {
            sims[i] = vectors.rowDot(position(components.get(i).movieId()), target);
            contribs[i] = components.get(i).weight() * sims[i] / norm;
            if (sims[i] > 0) {
                nSources++;
            }
        }

        List<Map<String, Object>> top = new ArrayList<>();
        for (int i : Num.topN(contribs, topN * 3)) {
            if (sims[i] <= 0 || top.size() >= topN) {
                break;
            }
            Component component = components.get(i);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("movieId", component.movieId());
            row.put("title", data.title(component.movieId()));
            row.put("your_rating", component.rating());
            row.put("profile_weight", round(component.weight(), 3));
            row.put("similarity", round(sims[i], 4));
            row.put("contribution", round(contribs[i], 5));
            top.add(row);
        }

        // Only the target's own terms can have a non-zero product.
        double[] vector = profile.vector();
        int lo = vectors.indptr[targetRow];
        int count = vectors.rowLength(targetRow);
        double[] products = new double[count];
        double score = 0.0;
        for (int i = 0; i < count; i++) {
            products[i] = vector[vectors.indices[lo + i]] * vectors.data[lo + i];
            score += products[i];
        }
        List<Map<String, Object>> termRows = new ArrayList<>();
        for (int i : Num.topN(products, topN)) {
            if (products[i] <= 0) {
                break;
            }
            int term = vectors.indices[lo + i];
            String[] info = pretty(terms[term]);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("term", info[0]);
            row.put("kind", info[1]);
            row.put("profile_weight", round(vector[term], 4));
            row.put("movie_weight", round(vectors.data[lo + i], 4));
            row.put("contribution", round(products[i], 4));
            termRows.add(row);
        }

        out.put("score", round(score, 4));
        out.put("because_of", top);
        out.put("terms", termRows);
        out.put("n_sources", nSources);
        return out;
    }

    /** Average rating per genre: the human-readable version of the profile. */
    public List<Map<String, Object>> genreProfile(Map<Integer, Double> rated) {
        Map<String, List<Double>> buckets = new LinkedHashMap<>();
        rated.forEach((movieId, rating) -> {
            Movie movie = data.movie(movieId);
            if (movie != null) {
                for (String genre : movie.genres()) {
                    buckets.computeIfAbsent(genre, key -> new ArrayList<>()).add(rating);
                }
            }
        });
        List<Map<String, Object>> rows = new ArrayList<>();
        buckets.forEach((genre, values) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("genre", genre);
            row.put("count", values.size());
            row.put("avg", round(Num.mean(values.stream().mapToDouble(Double::doubleValue).toArray()), 2));
            rows.add(row);
        });
        rows.sort(Comparator.<Map<String, Object>>comparingInt(r -> -(int) r.get("count"))
                .thenComparingDouble(r -> -(double) r.get("avg")));
        return new ArrayList<>(rows.subList(0, Math.min(12, rows.size())));
    }

    public Map<String, Object> stats() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("n_items", vectors.nRows);
        out.put("n_terms", vectors.nCols);
        out.put("nnz", vectors.nnz());
        out.put("avg_terms_per_movie", round(vectors.nnz() / (double) Math.max(1, vectors.nRows), 1));
        out.put("tmdb_enriched", enriched);
        return out;
    }

    /** Pairwise cosine average over a list, used for intra-list diversity. */
    double meanPairwiseSimilarity(int[] rows) {
        double[] scratch = new double[vectors.nCols];
        double sum = 0.0;
        for (int a = 0; a < rows.length; a++) {
            int row = rows[a];
            for (int p = vectors.indptr[row]; p < vectors.indptr[row + 1]; p++) {
                scratch[vectors.indices[p]] = vectors.data[p];
            }
            for (int b = a + 1; b < rows.length; b++) {
                sum += vectors.rowDot(rows[b], scratch);
            }
            for (int p = vectors.indptr[row]; p < vectors.indptr[row + 1]; p++) {
                scratch[vectors.indices[p]] = 0.0;
            }
        }
        int n = rows.length;
        return 2.0 * sum / (n * (n - 1));
    }
}
