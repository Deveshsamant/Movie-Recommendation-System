package com.movierec.engine;

import static com.movierec.math.Num.round;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.movierec.config.AppConfig;
import com.movierec.dataset.DatasetLoader;
import com.movierec.dataset.Movie;
import com.movierec.dataset.MovieData;
import com.movierec.math.CsrMatrix;
import com.movierec.math.Num;
import com.movierec.model.Baseline;
import com.movierec.model.BiasSvd;
import com.movierec.model.ContentBasedRecommender;
import com.movierec.model.Evaluator;
import com.movierec.model.HybridRecommender;
import com.movierec.model.ItemKnn;
import com.movierec.model.UserKnn;
import com.movierec.tmdb.Enricher;
import com.movierec.tmdb.TmdbClient;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.apache.commons.math3.linear.Array2DRowRealMatrix;
import org.apache.commons.math3.linear.EigenDecomposition;

/**
 * The recommender engine: loads data, trains every model, serves explanations.
 *
 * <p>Two sets of collaborative models are kept:
 *
 * <ul>
 *   <li>serving models, trained on all 100k ratings, used for recommendations
 *   <li>evaluation models, trained on the 80% training split only, used for the metrics page, so
 *       nothing is ever scored on data it was trained on
 * </ul>
 *
 * <p>Content-based item vectors depend on metadata alone, so a single content model serves both
 * roles.
 */
public final class Engine {

    public static final int CUSTOM_USER_BASE = 900000;

    /** A viewer built in the UI. Ratings keep the order they were given in. */
    public static final class CustomUser {
        public final int userId;
        public String name;
        public final Map<Integer, Double> ratings;
        public final double createdAt;

        CustomUser(int userId, String name, Map<Integer, Double> ratings, double createdAt) {
            this.userId = userId;
            this.name = name;
            this.ratings = ratings;
            this.createdAt = createdAt;
        }

        public Map<String, Object> toJson() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("userId", userId);
            out.put("name", name);
            out.put("ratings", new LinkedHashMap<>(ratings));
            out.put("created_at", createdAt);
            return out;
        }
    }

    public final AppConfig config;
    public final MovieData data;
    public final ItemKnn itemKnn;
    public final UserKnn userKnn;
    public final BiasSvd svd;
    public final HybridRecommender hybrid;

    private final TmdbClient tmdb;
    private final Enricher enricher;
    private final Object lock = new Object();
    private final Object evalLock = new Object();
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<Integer, CustomUser> customUsers = new LinkedHashMap<>();
    private final Path customPath;
    private final List<Map<String, Object>> buildLog = new ArrayList<>();
    private final Map<String, Map<String, Object>> evalCache = new HashMap<>();

    private final ItemKnn itemKnnEval;
    private final UserKnn userKnnEval;
    private final BiasSvd svdEval;
    private final Baseline baselineEval;

    private volatile ContentBasedRecommender content;
    private volatile String[] searchBlob;
    private volatile FactorSpace factorSpace;
    private int nextCustom = CUSTOM_USER_BASE;

    private record FactorSpace(double[][] basis, double[][] coords, List<Double> explained, double[] pop, double[] meanQ) {}

    public Engine(AppConfig config, TmdbClient tmdb, Enricher enricher, boolean verbose) {
        this.config = config;
        this.tmdb = tmdb;
        this.enricher = enricher;
        this.customPath = config.cacheDir.resolve("custom_users.json");

        if (verbose) {
            System.out.println("Building recommender engine...");
        }
        int k = config.integer("knn", "k_neighbors");
        double shrink = config.number("knn", "shrinkage");
        Map<String, Object> svdCfg = config.section("svd");
        Path cache = config.cacheDir;

        data = step("load MovieLens", () -> DatasetLoader.load(config), verbose);
        content = step("content model (TF-IDF)", () -> new ContentBasedRecommender(data, tmdb), verbose);
        itemKnn = step("item-kNN (full)", () -> ItemKnn.cached(data.ratingMatrix, k, shrink, "full", cache), verbose);
        userKnn = step("user-kNN (full)", () -> new UserKnn(data.ratingMatrix, k, shrink), verbose);
        svd = step("SVD (full)", () -> BiasSvd.cached(data.ratingMatrix, svdCfg, "full", verbose, cache), verbose);
        step("bias baseline (full)", () -> new Baseline(data.ratingMatrix), verbose);

        itemKnnEval = step("item-kNN (train split)", () -> ItemKnn.cached(data.train, k, shrink, "train", cache), verbose);
        userKnnEval = step("user-kNN (train split)", () -> new UserKnn(data.train, k, shrink), verbose);
        svdEval = step("SVD (train split)", () -> BiasSvd.cached(data.train, svdCfg, "train", verbose, cache), verbose);
        baselineEval = step("bias baseline (train split)", () -> new Baseline(data.train), verbose);

        hybrid = new HybridRecommender(data, content, itemKnn, userKnn, svd, config.section("hybrid"));
        loadCustomUsers();
        step("search index", () -> {
            buildSearchIndex();
            return null;
        }, verbose);
        if (verbose) {
            System.out.println("Engine ready.");
        }
    }

    private <T> T step(String label, Supplier<T> work, boolean verbose) {
        long t0 = System.nanoTime();
        T result = work.get();
        double elapsed = round((System.nanoTime() - t0) / 1e9, 2);
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("step", label);
        entry.put("seconds", elapsed);
        buildLog.add(entry);
        if (verbose) {
            System.out.printf(Locale.ROOT, "  [%5.2fs] %s%n", elapsed, label);
        }
        return result;
    }

    public ContentBasedRecommender content() {
        return content;
    }

    private static double now() {
        return System.currentTimeMillis() / 1000.0;
    }

    // ------------------------------------------------------------------- users
    public boolean isCustom(int userId) {
        return userId >= CUSTOM_USER_BASE;
    }

    /** Viewers you build yourself live on disk, so they survive a restart. */
    @SuppressWarnings("unchecked")
    private void loadCustomUsers() {
        if (!Files.exists(customPath)) {
            return;
        }
        Map<String, Object> blob;
        try {
            blob = mapper.readValue(customPath.toFile(), new TypeReference<>() {});
        } catch (IOException e) {
            return;
        }
        if (!(blob.get("users") instanceof List<?> users)) {
            return;
        }
        for (Object raw : users) {
            if (!(raw instanceof Map<?, ?> record) || !(record.get("userId") instanceof Number id)) {
                continue;
            }
            Map<Integer, Double> ratings = new LinkedHashMap<>();
            if (record.get("ratings") instanceof Map<?, ?> stored) {
                ((Map<String, Object>) stored).forEach((movieId, rating) ->
                        ratings.put(Integer.valueOf(movieId), ((Number) rating).doubleValue()));
            }
            Object name = record.get("name");
            Object created = record.get("created_at");
            customUsers.put(id.intValue(), new CustomUser(id.intValue(),
                    name == null || name.toString().isEmpty() ? "Guest" : name.toString(),
                    ratings,
                    created instanceof Number c ? c.doubleValue() : now()));
        }
        if (!customUsers.isEmpty()) {
            nextCustom = customUsers.keySet().stream().max(Integer::compare).orElse(CUSTOM_USER_BASE - 1) + 1;
        }
    }

    private void saveCustomUsers() {
        try {
            List<Map<String, Object>> users = new ArrayList<>();
            customUsers.values().forEach(user -> users.add(user.toJson()));
            Path tmp = customPath.resolveSibling("custom_users.tmp");
            mapper.writer(SerializationFeature.INDENT_OUTPUT).writeValue(tmp.toFile(), Map.of("users", users));
            Files.move(tmp, customPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            System.err.println("could not save viewers: " + e.getMessage());
        }
    }

    public CustomUser createCustomUser(String name, Map<Integer, Double> ratings) {
        synchronized (lock) {
            int userId = nextCustom++;
            CustomUser user = new CustomUser(userId,
                    name == null || name.isEmpty() ? "Guest " + (userId - CUSTOM_USER_BASE + 1) : name,
                    new LinkedHashMap<>(ratings), now());
            customUsers.put(userId, user);
            saveCustomUsers();
            return user;
        }
    }

    /** Sets or, when rating is null, clears a rating. Returns null for an unknown viewer. */
    public CustomUser setCustomRating(int userId, int movieId, Double rating) {
        synchronized (lock) {
            CustomUser user = customUsers.get(userId);
            if (user == null) {
                return null;
            }
            if (rating == null) {
                user.ratings.remove(movieId);
            } else {
                user.ratings.put(movieId, rating);
            }
            saveCustomUsers();
            return user;
        }
    }

    public CustomUser renameCustomUser(int userId, String name) {
        synchronized (lock) {
            CustomUser user = customUsers.get(userId);
            if (user == null) {
                return null;
            }
            if (name != null && !name.isEmpty()) {
                user.name = name;
            }
            saveCustomUsers();
            return user;
        }
    }

    public boolean deleteCustomUser(int userId) {
        synchronized (lock) {
            boolean gone = customUsers.remove(userId) != null;
            if (gone) {
                saveCustomUsers();
            }
            return gone;
        }
    }

    /** The viewer's display name when they exist as a custom user, else null. */
    public String customName(int userId) {
        synchronized (lock) {
            CustomUser user = customUsers.get(userId);
            return user == null ? null : user.name;
        }
    }

    /** movieId -> rating, for either a MovieLens user or a custom one. */
    public Map<Integer, Double> ratedMap(int userId) {
        if (isCustom(userId)) {
            synchronized (lock) {
                CustomUser user = customUsers.get(userId);
                return user == null ? new LinkedHashMap<>() : new LinkedHashMap<>(user.ratings);
            }
        }
        Map<Integer, Double> out = new LinkedHashMap<>();
        Integer u = data.userPos.get(userId);
        if (u != null) {
            CsrMatrix R = data.ratingMatrix;
            for (int p = R.indptr[u]; p < R.indptr[u + 1]; p++) {
                out.put(data.itemIds[R.indices[p]], R.data[p]);
            }
        }
        return out;
    }

    /** item position -> rating. */
    public Map<Integer, Double> ratedByPos(int userId) {
        Map<Integer, Double> out = new LinkedHashMap<>();
        ratedMap(userId).forEach((movieId, rating) -> {
            Integer pos = data.itemPos.get(movieId);
            if (pos != null) {
                out.put(pos, rating);
            }
        });
        return out;
    }

    /** Row index into the training matrices, or null for a custom user. */
    public Integer userRow(int userId) {
        return isCustom(userId) ? null : data.userPos.get(userId);
    }

    public Map<String, Object> listUsers(int limit) {
        CsrMatrix R = data.ratingMatrix;
        int[] counts = new int[data.nUsers()];
        for (int u = 0; u < counts.length; u++) {
            counts[u] = R.rowLength(u);
        }
        double[] means = R.rowMeans();
        int[] order = Num.argsortDescending(counts);

        List<Map<String, Object>> builtin = new ArrayList<>();
        for (int i = 0; i < Math.min(limit, order.length); i++) {
            int u = order[i];
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("userId", data.userIds[u]);
            row.put("name", "MovieLens user " + data.userIds[u]);
            row.put("n_ratings", counts[u]);
            row.put("avg_rating", round((float) means[u], 2)); // the original averages float32 ratings
            row.put("custom", false);
            row.put("top_genres", topGenresFor(u, 3));
            builtin.add(row);
        }

        List<Map<String, Object>> custom = new ArrayList<>();
        synchronized (lock) {
            for (CustomUser user : customUsers.values()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("userId", user.userId);
                row.put("name", user.name);
                row.put("n_ratings", user.ratings.size());
                row.put("avg_rating", user.ratings.isEmpty() ? 0.0
                        : round(Num.mean(user.ratings.values().stream().mapToDouble(Double::doubleValue).toArray()), 2));
                row.put("custom", true);
                row.put("top_genres", List.of());
                custom.add(row);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("builtin", builtin);
        out.put("custom", custom);
        return out;
    }

    private List<String> topGenresFor(int u, int n) {
        CsrMatrix R = data.ratingMatrix;
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (int p = R.indptr[u]; p < R.indptr[u + 1]; p++) {
            if (R.data[p] >= 3.5) {
                for (String genre : data.movies[R.indices[p]].genres()) {
                    counts.merge(genre, 1, Integer::sum);
                }
            }
        }
        return counts.entrySet().stream()
                .sorted(Comparator.comparingInt(e -> -e.getValue()))
                .limit(n)
                .map(Map.Entry::getKey)
                .toList();
    }

    // -------------------------------------------------------------- decoration
    @SuppressWarnings("unchecked")
    public static <T> List<T> first(Object list, int n) {
        if (!(list instanceof List<?> items)) {
            return new ArrayList<>();
        }
        return new ArrayList<>((List<T>) items.subList(0, Math.min(n, items.size())));
    }

    public Map<String, Object> movieCard(int movieId) {
        return movieCard(movieId, null);
    }

    public Map<String, Object> movieCard(int movieId, Map<String, Object> extra) {
        Map<String, Object> card = new LinkedHashMap<>();
        Movie movie = data.movie(movieId);
        if (movie == null) {
            card.put("movieId", movieId);
            card.put("title", "Unknown");
            if (extra != null) {
                card.putAll(extra);
            }
            return card;
        }
        card.put("movieId", movieId);
        card.put("title", movie.cleanTitle());
        card.put("full_title", movie.title());
        card.put("year", movie.year());
        card.put("genres", movie.genres());
        card.put("tags", first(movie.tags(), 6));
        card.put("n_ratings", movie.nRatings());
        card.put("avg_rating", round(movie.avgRating(), 2));
        card.put("tmdbId", movie.tmdbId());
        Map<String, Object> meta = tmdb.cachedOnly(movie.tmdbId());
        if (meta != null) {
            card.put("poster", meta.get("poster"));
            card.put("backdrop", meta.get("backdrop"));
            card.put("overview", meta.get("overview"));
            card.put("runtime", meta.get("runtime"));
            card.put("tmdb_score", meta.get("vote_average"));
            card.put("cast", first(meta.get("cast"), 4));
            card.put("directors", meta.getOrDefault("directors", List.of()));
            card.put("keywords", first(meta.get("keywords"), 6));
        }
        if (extra != null) {
            card.putAll(extra);
        }
        return card;
    }

    public Map<String, Object> tmdbMeta(int movieId) {
        Movie movie = data.movie(movieId);
        return movie == null ? null : tmdb.cachedOnly(movie.tmdbId());
    }

    /** Fetches any missing posters for a result set, in parallel. */
    public void hydratePosters(Iterable<Integer> movieIds) {
        if (!config.flag("posters_enabled")) {
            return;
        }
        List<TmdbClient.Target> wanted = new ArrayList<>();
        for (int movieId : movieIds) {
            Movie movie = data.movie(movieId);
            if (movie == null || (movie.tmdbId() != null && tmdb.cachedOnly(movie.tmdbId()) != null)) {
                continue;
            }
            wanted.add(new TmdbClient.Target(movie.tmdbId(), movie.cleanTitle(), movie.year()));
        }
        if (!wanted.isEmpty()) {
            tmdb.fetchMany(wanted, 16, true);
        }
    }

    // -------------------------------------------------------------- evaluation
    private Path evalPath(String key) {
        String tag = data.train.nnz() + "_" + content.vectors().nnz() + "_"
                + config.section("svd").get("n_factors") + "_" + config.section("svd").get("n_epochs") + "_"
                + config.section("knn").get("k_neighbors") + "_" + config.section("hybrid").get("alpha");
        return config.cacheDir.resolve("eval_" + key + "_" + tag + ".json");
    }

    /**
     * Scoring every model over 610 viewers takes a while, so it is cached to disk and keyed by
     * everything that could change the answer.
     */
    public Map<String, Object> evaluation(int k, boolean force) {
        String key = "k" + k + "_all";
        // A request arriving during the warm-up waits for that result rather than starting a second
        // full evaluation.
        synchronized (evalLock) {
            synchronized (lock) {
                if (!force && evalCache.containsKey(key)) {
                    return evalCache.get(key);
                }
            }
            Path path = evalPath(key);
            if (!force && Files.exists(path)) {
                try {
                    Map<String, Object> result = mapper.readValue(path.toFile(), new TypeReference<>() {});
                    synchronized (lock) {
                        evalCache.put(key, result);
                    }
                    return result;
                } catch (IOException ignored) {
                    // recompute below
                }
            }
            Evaluator evaluator = new Evaluator(data, content, itemKnnEval, userKnnEval, svdEval, baselineEval,
                    config.number("hybrid", "alpha"));
            Map<String, Object> result = evaluator.runFull(k);
            synchronized (lock) {
                evalCache.put(key, result);
            }
            try {
                mapper.writeValue(path.toFile(), result);
            } catch (IOException ignored) {
                // the next start simply recomputes
            }
            return result;
        }
    }

    /** Computes the scoreboard in the background so the page opens instantly. */
    public void warmEvaluation() {
        Thread thread = new Thread(() -> {
            try {
                evaluation(10, false);
            } catch (RuntimeException e) {
                System.err.println("evaluation failed: " + e);
            }
        }, "eval-warm");
        thread.setDaemon(true);
        thread.start();
    }

    // -------------------------------------------------------------- enrichment
    public boolean startEnrichment() {
        return enricher.start(data, 24, (done, total) -> {
            // Refresh the content model as TMDB data lands, at a few checkpoints.
            if (done != 0 && done % 1000 == 0) {
                swapContent(new ContentBasedRecommender(data, tmdb));
            }
        }, false);
    }

    private void swapContent(ContentBasedRecommender fresh) {
        synchronized (lock) {
            content = fresh;
            hybrid.setContent(fresh);
            evalCache.clear();
        }
    }

    public Map<String, Object> rebuildContent() {
        ContentBasedRecommender fresh = new ContentBasedRecommender(data, tmdb);
        swapContent(fresh);
        buildSearchIndex();
        return fresh.stats();
    }

    public Map<String, Object> enrichmentStatus() {
        return enricher.status();
    }

    // ----------------------------------------------------- latent factor space
    /**
     * Projects the item factor matrix Q down to 3D with PCA.
     *
     * <p>The SVD invents these axes purely to compress ratings, nobody labels them, yet the
     * projection lands on recognisable clusters. Computed once and cached, since it never changes
     * for a fixed model.
     */
    private FactorSpace factorSpace() {
        FactorSpace cached = factorSpace;
        if (cached != null) {
            return cached;
        }
        int F = svd.nFactors;
        int nItems = data.nItems();
        double[] Q = svd.itemFactors();
        double[] meanQ = new double[F];
        for (int i = 0; i < nItems; i++) {
            for (int f = 0; f < F; f++) {
                meanQ[f] += Q[i * F + f] / nItems;
            }
        }

        // The eigenvectors of the F x F scatter matrix are the principal axes; its eigenvalues are
        // the squared singular values.
        double[][] scatter = new double[F][F];
        double[] centred = new double[F];
        for (int i = 0; i < nItems; i++) {
            for (int f = 0; f < F; f++) {
                centred[f] = Q[i * F + f] - meanQ[f];
            }
            for (int a = 0; a < F; a++) {
                for (int b = 0; b < F; b++) {
                    scatter[a][b] += centred[a] * centred[b];
                }
            }
        }
        EigenDecomposition eigen = new EigenDecomposition(new Array2DRowRealMatrix(scatter, false));
        double[] variances = eigen.getRealEigenvalues();
        double total = 0.0;
        for (double v : variances) {
            total += Math.max(v, 0.0);
        }
        if (total == 0.0) {
            total = 1.0;
        }
        double[][] basis = new double[3][];
        List<Double> explained = new ArrayList<>();
        for (int axis = 0; axis < 3; axis++) {
            basis[axis] = eigen.getEigenvector(axis).toArray();
            explained.add(round(variances[axis] / total, 4));
        }

        double[][] coords = new double[nItems][3];
        double span = 0.0;
        for (int i = 0; i < nItems; i++) {
            for (int axis = 0; axis < 3; axis++) {
                double sum = 0.0;
                for (int f = 0; f < F; f++) {
                    sum += (Q[i * F + f] - meanQ[f]) * basis[axis][f];
                }
                coords[i][axis] = sum;
                span = Math.max(span, Math.abs(sum));
            }
        }
        if (span == 0.0) {
            span = 1.0;
        }
        for (double[] row : coords) {
            for (int axis = 0; axis < 3; axis++) {
                row[axis] /= span;
            }
        }

        double maxPop = 1.0;
        for (Movie movie : data.movies) {
            maxPop = Math.max(maxPop, movie.nRatings());
        }
        double[] pop = new double[nItems];
        for (int i = 0; i < nItems; i++) {
            pop[i] = data.movies[i].nRatings() / maxPop;
        }

        FactorSpace built = new FactorSpace(basis, coords, explained, pop, meanQ);
        factorSpace = built;
        return built;
    }

    /**
     * Point cloud for the front-end, with the viewer's own vector projected into the same space so
     * p_u can be drawn against the films.
     */
    public Map<String, Object> factorSpacePayload(int userId, int maxPoints) {
        FactorSpace space = factorSpace();
        Map<Integer, Double> ratedPos = ratedByPos(userId);
        int[] popularity = new int[data.nItems()];
        for (int i = 0; i < popularity.length; i++) {
            popularity[i] = data.movies[i].nRatings();
        }
        int[] order = Num.argsortDescending(popularity);

        // A viewer with 2,698 ratings would otherwise blow past the cap on their own, so their films
        // get at most half the budget, most-rated first.
        int mineBudget = maxPoints / 2;
        List<Integer> keep = new ArrayList<>();
        for (int pos : order) {
            if (keep.size() >= mineBudget) {
                break;
            }
            if (ratedPos.containsKey(pos)) {
                keep.add(pos);
            }
        }
        for (int pos : order) {
            if (keep.size() >= maxPoints) {
                break;
            }
            if (!ratedPos.containsKey(pos)) {
                keep.add(pos);
            }
        }

        List<Map<String, Object>> points = new ArrayList<>(keep.size());
        for (int pos : keep) {
            int movieId = data.itemIds[pos];
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("x", round(space.coords()[pos][0], 4));
            point.put("y", round(space.coords()[pos][1], 4));
            point.put("z", round(space.coords()[pos][2], 4));
            point.put("mine", ratedPos.containsKey(pos));
            point.put("pop", round(space.pop()[pos], 4));
            point.put("title", data.title(movieId));
            point.put("movieId", movieId);
            points.add(point);
        }

        Integer u = userRow(userId);
        BiasSvd.Factors viewer = u != null ? svd.userFactors(u) : svd.foldIn(ratedPos);
        double[] projected = new double[3];
        for (int axis = 0; axis < 3; axis++) {
            for (int f = 0; f < svd.nFactors; f++) {
                projected[axis] += (viewer.p()[f] - space.meanQ()[f]) * space.basis()[axis][f];
            }
        }
        double scale = 0.0;
        for (double[] row : space.coords()) {
            for (double value : row) {
                scale = Math.max(scale, Math.abs(value));
            }
        }
        if (scale == 0.0) {
            scale = 1.0;
        }
        double norm = Num.norm(projected);
        if (norm == 0.0) {
            norm = 1.0;
        }
        Map<String, Object> vector = new LinkedHashMap<>();
        vector.put("x", round(projected[0] / norm * 0.9 * scale, 4));
        vector.put("y", round(projected[1] / norm * 0.9 * scale, 4));
        vector.put("z", round(projected[2] / norm * 0.9 * scale, 4));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("points", points);
        out.put("user_vector", vector);
        out.put("explained", space.explained());
        out.put("n_factors", svd.nFactors);
        out.put("n_rated", ratedPos.size());
        return out;
    }

    // ------------------------------------------------------------------ search
    /**
     * One lowercase haystack per film: title, tags, cast, crew, keywords.
     *
     * <p>Without this, searching is title-only and "nolan", "ghibli" or "heist" find nothing, even
     * though that metadata is already cached.
     */
    private void buildSearchIndex() {
        String[] blobs = new String[data.nItems()];
        for (int i = 0; i < blobs.length; i++) {
            Movie movie = data.movies[i];
            List<String> parts = new ArrayList<>();
            parts.add(movie.cleanTitle());
            parts.add(String.join(" ", movie.genres()));
            parts.add(String.join(" ", Engine.<String>first(movie.tags(), 12)));
            Map<String, Object> meta = tmdb.cachedOnly(movie.tmdbId());
            if (meta != null) {
                parts.add(String.join(" ", Engine.<String>first(meta.get("cast"), 6)));
                parts.add(String.join(" ", Engine.<String>first(meta.get("directors"), Integer.MAX_VALUE)));
                parts.add(String.join(" ", Engine.<String>first(meta.get("keywords"), 12)));
                parts.add(String.join(" ", Engine.<String>first(meta.get("tmdb_genres"), Integer.MAX_VALUE)));
            }
            blobs[i] = String.join(" ", parts).toLowerCase(Locale.ROOT);
        }
        searchBlob = blobs;
    }

    /** Most-rated first, then best-rated. Stable, so remaining ties keep catalogue order. */
    public static final Comparator<Movie> BY_POPULARITY =
            Comparator.comparingInt((Movie m) -> -m.nRatings()).thenComparingDouble(m -> -m.avgRating());

    /** Title matches first, then anything matching the wider metadata. */
    public List<Integer> search(String query, int limit) {
        String needle = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        List<Integer> out = new ArrayList<>();
        if (needle.isEmpty()) {
            return out;
        }
        List<List<Movie>> buckets = List.of(new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        String[] blobs = searchBlob;
        for (int i = 0; i < data.movies.length; i++) {
            Movie movie = data.movies[i];
            String title = movie.cleanTitle().toLowerCase(Locale.ROOT);
            if (title.equals(needle)) {
                buckets.get(0).add(movie);
            } else if (title.startsWith(needle)) {
                buckets.get(1).add(movie);
            } else if (title.contains(needle)) {
                buckets.get(2).add(movie);
            } else if (blobs != null && blobs[i].contains(needle)) {
                buckets.get(3).add(movie);
            }
        }
        for (List<Movie> bucket : buckets) {
            bucket.sort(BY_POPULARITY);
            for (Movie movie : bucket) {
                out.add(movie.movieId());
                if (out.size() >= limit) {
                    return out;
                }
            }
        }
        return out;
    }

    // -------------------------------------------------------------------- meta
    public Map<String, Object> status() {
        Map<String, Object> itemModel = new LinkedHashMap<>();
        itemModel.put("k", itemKnn.k);
        itemModel.put("shrinkage", config.section("knn").get("shrinkage"));
        itemModel.put("stored_similarities", itemKnn.S.nnz());
        Map<String, Object> userModel = new LinkedHashMap<>();
        userModel.put("k", userKnn.k);
        userModel.put("shrinkage", config.section("knn").get("shrinkage"));
        Map<String, Object> svdModel = new LinkedHashMap<>();
        svdModel.put("n_factors", svd.nFactors);
        svdModel.put("n_epochs", svd.nEpochs);
        svdModel.put("lr", svd.lr);
        svdModel.put("reg", svd.reg);
        svdModel.put("final_train_rmse", svd.history.isEmpty() ? null : svd.history.get(svd.history.size() - 1).get("train_rmse"));
        svdModel.put("history", svd.history);
        Map<String, Object> models = new LinkedHashMap<>();
        models.put("item_knn", itemModel);
        models.put("user_knn", userModel);
        models.put("svd", svdModel);
        models.put("hybrid", config.section("hybrid"));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dataset", data.stats());
        out.put("content", content.stats());
        out.put("models", models);
        out.put("tmdb", tmdb.routeStatus());
        out.put("enrichment", enricher.status());
        out.put("build_log", buildLog);
        return out;
    }

    /** Distinct ids in first-seen order, for hydrating a batch without repeats. */
    public static Set<Integer> distinct(List<Integer> ids) {
        return new LinkedHashSet<>(ids);
    }
}
