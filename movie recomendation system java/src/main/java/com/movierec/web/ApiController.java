package com.movierec.web;

import static com.movierec.math.Num.round;

import com.movierec.dataset.DatasetSummary;
import com.movierec.dataset.Movie;
import com.movierec.engine.Engine;
import com.movierec.math.CsrMatrix;
import com.movierec.math.Num;
import com.movierec.math.Pcg64Random;
import com.movierec.model.BiasSvd;
import com.movierec.model.Cf;
import com.movierec.model.ContentBasedRecommender;
import com.movierec.model.HybridRecommender;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * One endpoint per idea the UI needs to show.
 *
 * <p>Every recommendation endpoint returns both the ranked list and the arithmetic behind it, so
 * the front-end never has to invent an explanation.
 */
@RestController
@RequestMapping("/api")
public class ApiController {

    private final Engine e;
    private volatile Map<String, Object> overviewSummary;

    public ApiController(Engine engine) {
        this.e = engine;
    }

    private static Map<String, Object> json(Object... pairs) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            out.put((String) pairs[i], pairs[i + 1]);
        }
        return out;
    }

    private static ResponseStatusException error(HttpStatus status, String detail) {
        return new ResponseStatusException(status, detail);
    }

    // -------------------------------------------------------------------- meta
    @GetMapping("/status")
    public Map<String, Object> status() {
        return e.status();
    }

    @PostMapping("/enrich/start")
    public Map<String, Object> enrichStart() {
        boolean started = e.startEnrichment();
        return json("started", started, "status", e.enrichmentStatus());
    }

    @GetMapping("/enrich/status")
    public Map<String, Object> enrichStatus() {
        return e.enrichmentStatus();
    }

    /** Statistics about the ratings themselves, for the charts in the guided overview. */
    @GetMapping("/overview")
    public Map<String, Object> overview() {
        Map<String, Object> summary = overviewSummary;
        if (summary == null) {
            summary = DatasetSummary.of(e.data);
            overviewSummary = summary;
        }
        return summary;
    }

    @PostMapping("/content/rebuild")
    public Map<String, Object> contentRebuild() {
        return e.rebuildContent();
    }

    // ------------------------------------------------------------------- users
    @GetMapping("/users")
    public Map<String, Object> listUsers(@RequestParam(defaultValue = "80") int limit) {
        return e.listUsers(limit);
    }

    @PostMapping("/users")
    public Map<String, Object> createUser(@RequestBody Map<String, Object> payload) {
        Object name = payload.getOrDefault("name", "");
        if (!(name instanceof String)) {
            throw error(HttpStatus.UNPROCESSABLE_ENTITY, "name must be a string");
        }
        Map<Integer, Double> ratings = new LinkedHashMap<>();
        Object given = payload.getOrDefault("ratings", Map.of());
        if (!(given instanceof Map<?, ?> rows)) {
            throw error(HttpStatus.UNPROCESSABLE_ENTITY, "ratings must be an object");
        }
        try {
            rows.forEach((movieId, rating) ->
                    ratings.put(Integer.valueOf(movieId.toString()), Double.valueOf(rating.toString())));
        } catch (NumberFormatException | NullPointerException bad) {
            throw error(HttpStatus.UNPROCESSABLE_ENTITY, "ratings must map movie ids to numbers");
        }
        return e.createCustomUser((String) name, ratings).toJson();
    }

    @DeleteMapping("/users/{userId}")
    public Map<String, Object> deleteUser(@PathVariable int userId) {
        return json("deleted", e.deleteCustomUser(userId));
    }

    @PostMapping("/users/{userId}/ratings")
    public Map<String, Object> setRating(@PathVariable int userId, @RequestBody Map<String, Object> payload) {
        if (!(payload.get("movieId") instanceof Number movieId)) {
            throw error(HttpStatus.UNPROCESSABLE_ENTITY, "movieId is required");
        }
        Object rating = payload.get("rating");
        if (rating != null && !(rating instanceof Number)) {
            throw error(HttpStatus.UNPROCESSABLE_ENTITY, "rating must be a number or null");
        }
        Double value = rating == null ? null : ((Number) rating).doubleValue();
        Engine.CustomUser user = e.setCustomRating(userId, movieId.intValue(), value);
        if (user == null) {
            throw error(HttpStatus.NOT_FOUND, "only viewers you created can be re-rated");
        }
        return json("userId", user.userId, "n_ratings", user.ratings.size(), "readiness", readiness(user.ratings.size()));
    }

    @PatchMapping("/users/{userId}")
    public Map<String, Object> renameUser(@PathVariable int userId, @RequestBody Map<String, Object> payload) {
        if (!(payload.get("name") instanceof String name)) {
            throw error(HttpStatus.UNPROCESSABLE_ENTITY, "name is required");
        }
        Engine.CustomUser user = e.renameCustomUser(userId, name.strip());
        if (user == null) {
            throw error(HttpStatus.NOT_FOUND, "custom user not found");
        }
        return json("userId", user.userId, "name", user.name);
    }

    /** What each approach can actually do with this many ratings. */
    private Map<String, Object> readiness(int n) {
        int threshold = e.config.integer("hybrid", "cold_start_threshold");
        String message;
        if (n < 1) {
            message = "Rate a film to switch content-based filtering on.";
        } else if (n < 3) {
            message = "Content-based filtering is working. Collaborative filtering needs a few more.";
        } else if (n < threshold) {
            message = "Both approaches are running, but the hybrid still falls back to content below " + threshold + ".";
        } else {
            message = "All three approaches have enough to work with.";
        }
        return json("n_ratings", n, "cold_start_threshold", threshold, "content", n >= 1,
                "collaborative", n >= 3, "hybrid_uses_cf", n >= threshold, "message", message);
    }

    private static List<Map.Entry<Integer, Double>> byRatingDesc(Map<Integer, Double> rated, int limit) {
        List<Map.Entry<Integer, Double>> ordered = new ArrayList<>(rated.entrySet());
        ordered.sort(Comparator.comparingDouble(entry -> -entry.getValue()));
        return ordered.subList(0, Math.min(Math.max(0, limit), ordered.size()));
    }

    private static List<Integer> idsOf(List<Map.Entry<Integer, Double>> entries) {
        return entries.stream().map(Map.Entry::getKey).toList();
    }

    /**
     * A viewer's ratings, highest first.
     *
     * <p>Capped by default: the busiest MovieLens viewer has 2,698 ratings, and hydrating a poster
     * for every one of them costs seconds for a grid nobody scrolls to the bottom of.
     */
    @GetMapping("/users/{userId}/ratings")
    public Map<String, Object> listRatings(@PathVariable int userId, @RequestParam(defaultValue = "60") int limit) {
        Map<Integer, Double> rated = e.ratedMap(userId);
        if (rated.isEmpty() && !e.isCustom(userId)) {
            throw error(HttpStatus.NOT_FOUND, "user not found");
        }
        List<Map.Entry<Integer, Double>> ordered = byRatingDesc(rated, Math.max(1, Math.min(limit, 300)));
        e.hydratePosters(idsOf(ordered));
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map.Entry<Integer, Double> entry : ordered) {
            items.add(e.movieCard(entry.getKey(), json("your_rating", entry.getValue())));
        }
        return json("userId", userId, "editable", e.isCustom(userId), "n_ratings", rated.size(),
                "readiness", readiness(rated.size()), "items", items);
    }

    @GetMapping("/users/{userId}")
    public Map<String, Object> userDetail(@PathVariable int userId, @RequestParam(defaultValue = "12") int top) {
        Map<Integer, Double> rated = e.ratedMap(userId);
        if (rated.isEmpty() && !e.isCustom(userId)) {
            throw error(HttpStatus.NOT_FOUND, "user not found");
        }
        List<Map.Entry<Integer, Double>> ordered = byRatingDesc(rated, top);
        e.hydratePosters(idsOf(ordered));
        double[] values = rated.values().stream().mapToDouble(Double::doubleValue).toArray();
        String custom = e.isCustom(userId) ? e.customName(userId) : null;
        List<Map<String, Object>> topRated = new ArrayList<>();
        for (Map.Entry<Integer, Double> entry : ordered) {
            topRated.add(e.movieCard(entry.getKey(), json("your_rating", entry.getValue())));
        }
        return json(
                "userId", userId,
                "name", custom != null ? custom : "MovieLens user " + userId,
                "custom", e.isCustom(userId),
                "n_ratings", rated.size(),
                "avg_rating", values.length > 0 ? round(Num.mean(values), 2) : 0.0,
                "rating_spread", values.length > 0 ? round(Num.std(values), 2) : 0.0,
                "genre_profile", e.content().genreProfile(rated),
                "top_rated", topRated,
                "histogram", histogram(values));
    }

    private static List<Map<String, Object>> histogram(double[] values) {
        int[] buckets = new int[10];
        for (double v : values) {
            buckets[Math.min(9, Math.max(0, (int) Math.rint(v * 2) - 1))]++;
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            out.add(json("rating", round((i + 1) * 0.5, 1), "count", buckets[i]));
        }
        return out;
    }

    // --------------------------------------------------------------- catalogue
    /** Matches title first, then cast, director, keywords, tags and genres. */
    @GetMapping("/movies/search")
    public Map<String, Object> search(@RequestParam String q, @RequestParam(defaultValue = "20") int limit) {
        if (q.isEmpty()) {
            throw error(HttpStatus.UNPROCESSABLE_ENTITY, "q must not be empty");
        }
        List<Integer> ids = e.search(q, limit);
        e.hydratePosters(ids);
        return json("query", q, "results", cards(ids));
    }

    private List<Map<String, Object>> cards(List<Integer> ids) {
        List<Map<String, Object>> out = new ArrayList<>(ids.size());
        for (int id : ids) {
            out.add(e.movieCard(id));
        }
        return out;
    }

    @GetMapping("/movies/popular")
    public Map<String, Object> popular(@RequestParam(defaultValue = "24") int limit,
                                       @RequestParam(name = "min_ratings", defaultValue = "40") int minRatings,
                                       @RequestParam(required = false) String genre) {
        List<Integer> ids = Arrays.stream(e.data.movies)
                .filter(m -> m.nRatings() >= minRatings)
                .filter(m -> genre == null || genre.isEmpty() || m.genres().contains(genre))
                .sorted(Engine.BY_POPULARITY)
                .limit(Math.max(0, limit))
                .map(Movie::movieId)
                .toList();
        e.hydratePosters(ids);
        return json("results", cards(ids));
    }

    /** A spread of well-known films across genres, for rating a new profile. */
    @GetMapping("/movies/sampler")
    public Map<String, Object> sampler(@RequestParam(defaultValue = "30") int limit,
                                       @RequestParam(defaultValue = "0") long seed) {
        if (seed < 0) {
            throw error(HttpStatus.UNPROCESSABLE_ENTITY, "seed must be non-negative");
        }
        Pcg64Random rng = new Pcg64Random(seed);
        List<Integer> picked = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        for (String genre : e.data.allGenres) {
            int[] pool = Arrays.stream(e.data.movies)
                    .filter(m -> m.nRatings() >= 50 && m.genres().contains(genre))
                    .sorted(Comparator.comparingInt((Movie m) -> -m.nRatings()))
                    .limit(30)
                    .mapToInt(Movie::movieId)
                    .toArray();
            if (pool.length == 0) {
                continue;
            }
            rng.shuffle(pool);
            for (int i = 0; i < Math.min(2, pool.length); i++) {
                if (seen.add(pool[i])) {
                    picked.add(pool[i]);
                }
            }
        }
        List<Integer> kept = picked.subList(0, Math.min(Math.max(0, limit), picked.size()));
        e.hydratePosters(kept);
        return json("results", cards(kept));
    }

    /**
     * Full record for the info popup: overview, cast, director, TMDB rating.
     *
     * <p>Goes through hydratePosters so a dead MovieLens tmdbId still resolves via the title search
     * rather than returning a bare catalogue row.
     */
    @GetMapping("/movies/{movieId}")
    public Map<String, Object> movieDetail(@PathVariable int movieId) {
        if (!e.data.itemPos.containsKey(movieId)) {
            throw error(HttpStatus.NOT_FOUND, "movie not found");
        }
        e.hydratePosters(List.of(movieId));
        Map<String, Object> card = e.movieCard(movieId);
        Map<String, Object> meta = e.tmdbMeta(movieId);
        if (meta != null) {
            card.put("cast", Engine.first(meta.get("cast"), 8));
            card.put("directors", meta.getOrDefault("directors", List.of()));
            card.put("keywords", Engine.first(meta.get("keywords"), 10));
            card.put("tagline", meta.getOrDefault("tagline", ""));
            card.put("release_date", meta.getOrDefault("release_date", ""));
            card.put("vote_count", meta.get("vote_count"));
            card.put("tmdb_genres", meta.getOrDefault("tmdb_genres", List.of()));
        }
        return card;
    }

    @GetMapping("/genres")
    public Map<String, Object> genres() {
        return json("genres", e.data.allGenres);
    }

    // --------------------------------------------------------- 1. content-based
    @GetMapping("/recommend/content")
    public Map<String, Object> recommendContent(@RequestParam("user_id") int userId, @RequestParam(defaultValue = "12") int n) {
        Map<Integer, Double> rated = e.ratedMap(userId);
        if (rated.isEmpty()) {
            return json("items", List.of(), "empty", true,
                    "message", "Rate a few movies first - content filtering needs a taste profile.");
        }
        ContentBasedRecommender content = e.content();
        ContentBasedRecommender.Profile profile = content.buildProfile(rated);
        ContentBasedRecommender.Recommendation result = content.recommend(rated, n, profile);
        e.hydratePosters(result.items().stream().map(ContentBasedRecommender.Scored::movieId).toList());

        List<Map<String, Object>> items = new ArrayList<>();
        for (ContentBasedRecommender.Scored item : result.items()) {
            Map<String, Object> why = content.explainRecommendation(profile, item.movieId(), 5);
            items.add(e.movieCard(item.movieId(), json("score", round(item.score(), 4), "explanation", why)));
        }
        return json(
                "items", items,
                "profile_terms", result.profileTerms(),
                "genre_profile", content.genreProfile(rated),
                "n_ratings", rated.size(),
                "method", json(
                        "name", "Content-based filtering",
                        "vector_space", content.stats(),
                        "formula", "score(j) = cos(profile, v_j) = sum_t profile[t] * tfidf_j[t]",
                        "profile_formula", "profile = normalise( sum_i (r_i - mean_u)/sd_u * v_i )"));
    }

    @GetMapping("/explain/content")
    @SuppressWarnings("unchecked")
    public Map<String, Object> explainContent(@RequestParam("user_id") int userId, @RequestParam("movie_id") int movieId) {
        ContentBasedRecommender content = e.content();
        ContentBasedRecommender.Profile profile = content.buildProfile(e.ratedMap(userId));
        Map<String, Object> why = content.explainRecommendation(profile, movieId, 8);
        for (Map<String, Object> source : (List<Map<String, Object>>) why.get("because_of")) {
            source.putAll(e.movieCard((int) source.get("movieId")));
        }
        return json("movie", e.movieCard(movieId), "explanation", why);
    }

    @GetMapping("/similar/{movieId}")
    public Map<String, Object> similar(@PathVariable int movieId,
                                       @RequestParam(defaultValue = "content") String method,
                                       @RequestParam(defaultValue = "12") int n) {
        Integer pos = e.data.itemPos.get(movieId);
        if (pos == null) {
            throw error(HttpStatus.NOT_FOUND, "movie not found");
        }
        boolean byContent = method.equals("content");
        ContentBasedRecommender content = e.content();
        List<Integer> ids = new ArrayList<>();
        List<Double> scores = new ArrayList<>();
        if (byContent) {
            for (ContentBasedRecommender.Scored row : content.similarToMovie(movieId, n)) {
                ids.add(row.movieId());
                scores.add(row.score());
            }
        } else {
            for (double[] row : e.itemKnn.similarItems(pos, n)) {
                ids.add(e.data.itemIds[(int) row[0]]);
                scores.add(row[1]);
            }
        }
        e.hydratePosters(ids);
        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            Map<String, Object> extra = json("score", round(scores.get(i), 4));
            if (byContent) {
                extra.put("explanation", content.explainPair(movieId, ids.get(i), 8));
            }
            items.add(e.movieCard(ids.get(i), extra));
        }
        return json("seed", e.movieCard(movieId), "method", method, "items", items);
    }

    // --------------------------------------------------------- 2. collaborative
    @GetMapping("/recommend/collaborative")
    public Map<String, Object> recommendCollaborative(@RequestParam("user_id") int userId,
                                                      @RequestParam(defaultValue = "item-knn") String model,
                                                      @RequestParam(defaultValue = "12") int n) {
        Map<Integer, Double> ratedPos = e.ratedByPos(userId);
        if (ratedPos.isEmpty()) {
            return json("items", List.of(), "empty", true,
                    "message", "Rate a few movies first - collaborative filtering needs ratings to compare.");
        }
        Integer u = e.userRow(userId);
        Map<Integer, Double> ratings = u != null ? null : ratedPos;
        Map<String, Object> fold = null;
        List<Cf.Rec> rows;
        switch (model) {
            case "svd" -> {
                BiasSvd.Factors viewer = viewerFactors(u, ratedPos);
                if (u == null) {
                    fold = json("b_u", round(viewer.b(), 4), "p_u_norm", round(Num.norm(viewer.p()), 4),
                            "n_factors", e.svd.nFactors);
                }
                rows = e.svd.recommend(viewer, n, u != null ? e.svd.seenBy(u) : ratedPos.keySet());
            }
            case "user-knn" -> rows = e.userKnn.recommend(u, ratings, n);
            default -> rows = e.itemKnn.recommend(u, ratings, n);
        }

        List<Integer> movieIds = rows.stream().map(r -> e.data.itemIds[r.pos()]).toList();
        e.hydratePosters(movieIds);

        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            Cf.Rec row = rows.get(i);
            items.add(e.movieCard(movieIds.get(i), json(
                    "score", round(row.rank(), 4),
                    "predicted_rating", round(row.predicted(), 2),
                    "support", round(row.support(), 3),
                    "explanation", explainCf(row.pos(), model, ratedPos, u))));
        }
        Map<String, Object> meta = cfMeta(model);
        if (fold != null) {
            meta.put("fold_in", fold);
        }
        return json("items", items, "model", model, "method", meta, "n_ratings", ratedPos.size());
    }

    private BiasSvd.Factors viewerFactors(Integer u, Map<Integer, Double> ratedPos) {
        return u != null ? e.svd.userFactors(u) : e.svd.foldIn(ratedPos);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> explainCf(int pos, String model, Map<Integer, Double> ratedPos, Integer u) {
        if (model.equals("svd")) {
            return e.svd.explain(pos, viewerFactors(u, ratedPos));
        }
        Map<Integer, Double> ratings = u != null ? null : ratedPos;
        Map<String, Object> why = model.equals("user-knn")
                ? e.userKnn.explain(pos, u, ratings)
                : e.itemKnn.explain(pos, u, ratings);
        for (Map<String, Object> neighbour : (List<Map<String, Object>>) why.get("neighbours")) {
            if (neighbour.containsKey("item_pos")) {
                int movieId = e.data.itemIds[(int) neighbour.get("item_pos")];
                neighbour.put("movieId", movieId);
                neighbour.put("title", e.data.title(movieId));
            }
            if (neighbour.containsKey("user_pos")) {
                neighbour.put("userId", e.data.userIds[(int) neighbour.get("user_pos")]);
            }
        }
        return why;
    }

    private Map<String, Object> cfMeta(String model) {
        Object shrinkage = e.config.section("knn").get("shrinkage");
        if (model.equals("svd")) {
            return json(
                    "name", "Matrix factorisation (BiasSVD)",
                    "formula", "r_hat(u,i) = mu + b_u + b_i + p_u . q_i",
                    "params", json("n_factors", e.svd.nFactors, "n_epochs", e.svd.nEpochs, "lr", e.svd.lr, "reg", e.svd.reg),
                    "mu", round(e.svd.mu(), 4),
                    "training", e.svd.history,
                    "note", "Ranked on the unclipped score; the predicted rating is the clipped value.");
        }
        if (model.equals("user-knn")) {
            return json(
                    "name", "User-based kNN (Pearson)",
                    "formula", "r_hat(u,i) = mean_u + sum_v sim(u,v)(r_vi - mean_v) / sum_v |sim(u,v)|",
                    "rank_formula", "rank(u,i) = sum_v sim(u,v) * (r_vi - mean_v)",
                    "params", json("k", e.userKnn.k, "shrinkage", shrinkage));
        }
        return json(
                "name", "Item-based kNN (adjusted cosine)",
                "formula", "r_hat(u,i) = mean_u + sum_j sim(i,j)(r_uj - mean_u) / sum_j |sim(i,j)|",
                "rank_formula", "rank(u,i) = sum_j sim(i,j) * (r_uj - mean_u)",
                "params", json("k", e.itemKnn.k, "shrinkage", shrinkage, "stored_similarities", e.itemKnn.S.nnz()));
    }

    @GetMapping("/explain/collaborative")
    public Map<String, Object> explainCollaborative(@RequestParam("user_id") int userId,
                                                    @RequestParam("movie_id") int movieId,
                                                    @RequestParam(defaultValue = "item-knn") String model) {
        Integer pos = e.data.itemPos.get(movieId);
        if (pos == null) {
            throw error(HttpStatus.NOT_FOUND, "movie not found");
        }
        Map<String, Object> why = explainCf(pos, model, e.ratedByPos(userId), e.userRow(userId));
        return json("movie", e.movieCard(movieId), "model", model, "explanation", why, "method", cfMeta(model));
    }

    /** Which other users look most like this one, and what they loved. */
    @GetMapping("/neighbours")
    public Map<String, Object> neighbours(@RequestParam("user_id") int userId, @RequestParam(defaultValue = "10") int n) {
        Integer u = e.userRow(userId);
        Map<Integer, Double> ratedPos = e.ratedByPos(userId);
        if (ratedPos.isEmpty()) {
            return json("neighbours", List.of());
        }
        CsrMatrix R = e.data.ratingMatrix;
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : e.userKnn.similarUsers(u, u != null ? null : ratedPos, n)) {
            int v = (int) row.get("user_pos");
            int shared = 0;
            List<double[]> loved = new ArrayList<>();
            for (int p = R.indptr[v]; p < R.indptr[v + 1]; p++) {
                int item = R.indices[p];
                if (ratedPos.containsKey(item)) {
                    shared++;
                } else if (R.data[p] >= 4.5) {
                    loved.add(new double[] {item, R.data[p]});
                }
            }
            loved.sort(Comparator.comparingDouble(pair -> -pair[1]));
            List<double[]> top = loved.subList(0, Math.min(4, loved.size()));
            List<Integer> movieIds = top.stream().map(pair -> e.data.itemIds[(int) pair[0]]).toList();
            e.hydratePosters(movieIds);

            List<Map<String, Object>> cards = new ArrayList<>();
            for (int i = 0; i < top.size(); i++) {
                cards.add(e.movieCard(movieIds.get(i), json("their_rating", top.get(i)[1])));
            }
            row.put("userId", e.data.userIds[v]);
            row.put("shared", shared);
            row.put("loved", cards);
            out.add(row);
        }
        return json("neighbours", out, "your_ratings", ratedPos.size());
    }

    /** PCA projection of the learned item factors, for the 3D point cloud. */
    @GetMapping("/svd/space")
    public Map<String, Object> svdSpace(@RequestParam("user_id") int userId,
                                        @RequestParam(name = "max_points", defaultValue = "1600") int maxPoints) {
        return e.factorSpacePayload(userId, maxPoints);
    }

    /** The two poles of one latent factor: what the model discovered by itself. */
    @GetMapping("/svd/factors")
    public Map<String, Object> svdFactors(@RequestParam(defaultValue = "0") int k, @RequestParam(defaultValue = "8") int n) {
        if (k < 0 || k >= e.svd.nFactors) {
            throw error(HttpStatus.BAD_REQUEST, "factor out of range");
        }
        int nItems = e.data.nItems();
        double[] high = new double[nItems];
        double[] low = new double[nItems];
        for (int i = 0; i < nItems; i++) {
            boolean popular = e.data.movies[i].nRatings() >= 25;
            double loading = e.svd.itemFactor(i, k);
            high[i] = popular ? loading : -1e9;
            low[i] = popular ? -loading : -1e9;
        }
        int[] top = Num.topN(high, n);
        int[] bottom = Num.topN(low, n);

        List<Integer> ids = new ArrayList<>();
        for (int[] poles : new int[][] {top, bottom}) {
            for (int pos : poles) {
                ids.add(e.data.itemIds[pos]);
            }
        }
        e.hydratePosters(ids);
        return json("factor", k, "n_factors", e.svd.nFactors,
                "positive", factorCards(top, k), "negative", factorCards(bottom, k));
    }

    private List<Map<String, Object>> factorCards(int[] positions, int k) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (int pos : positions) {
            out.add(e.movieCard(e.data.itemIds[pos], json("loading", round(e.svd.itemFactor(pos, k), 4))));
        }
        return out;
    }

    // ---------------------------------------------------------------- 3. hybrid
    @GetMapping("/recommend/hybrid")
    public Map<String, Object> recommendHybrid(@RequestParam("user_id") int userId,
                                               @RequestParam(defaultValue = "12") int n,
                                               @RequestParam(defaultValue = "weighted") String strategy,
                                               @RequestParam(name = "cf_model", defaultValue = "item-knn") String cfModel,
                                               @RequestParam(required = false) Double alpha) {
        Map<Integer, Double> ratedPos = e.ratedByPos(userId);
        if (ratedPos.isEmpty()) {
            return json("items", List.of(), "empty", true,
                    "message", "Rate a few movies first - the hybrid needs something to blend.");
        }
        HybridRecommender.Result result = e.hybrid.recommend(e.userRow(userId), ratedPos, n, strategy, cfModel, alpha);
        e.hydratePosters(result.items().stream().map(HybridRecommender.Item::movieId).toList());

        List<Map<String, Object>> items = new ArrayList<>();
        for (HybridRecommender.Item item : result.items()) {
            items.add(e.movieCard(item.movieId(), json(
                    "score", round(item.score(), 4),
                    "content_raw", round(item.contentRaw(), 4),
                    "content_norm", round(item.contentNorm(), 4),
                    "cf_raw", round(item.cfRaw(), 4),
                    "cf_norm", round(item.cfNorm(), 4),
                    "predicted_rating", round(item.cfRating(), 2),
                    "content_part", round(item.contentPart(), 4),
                    "cf_part", round(item.cfPart(), 4),
                    "lead", item.lead())));
        }
        return json(
                "items", items,
                "trace", result.trace(),
                "method", json(
                        "name", "Hybrid - " + strategy,
                        "formula", hybridFormula(strategy),
                        "strategies", HybridRecommender.STRATEGIES),
                "n_ratings", ratedPos.size());
    }

    private static String hybridFormula(String strategy) {
        return switch (strategy) {
            case "weighted" -> "score = alpha * norm(CF) + (1 - alpha) * norm(content)";
            case "switching" -> "score = content if n_ratings < threshold else CF";
            case "rank" -> "score = alpha / (60 + rank_CF) + (1 - alpha) / (60 + rank_content)";
            case "cascade" -> "CF selects the candidate pool, content re-ranks inside it";
            default -> "";
        };
    }

    @GetMapping("/hybrid/alpha-sweep")
    @SuppressWarnings("unchecked")
    public Map<String, Object> alphaSweep(@RequestParam("user_id") int userId,
                                          @RequestParam(name = "cf_model", defaultValue = "item-knn") String cfModel,
                                          @RequestParam(defaultValue = "8") int n) {
        Map<Integer, Double> ratedPos = e.ratedByPos(userId);
        if (ratedPos.isEmpty()) {
            return json("sweep", List.of());
        }
        List<Map<String, Object>> sweep = e.hybrid.alphaSweep(e.userRow(userId), ratedPos, cfModel, n);
        for (Map<String, Object> step : sweep) {
            List<double[]> rows = (List<double[]>) step.get("items");
            e.hydratePosters(rows.stream().map(row -> (int) row[0]).toList());
            List<Map<String, Object>> cards = new ArrayList<>();
            for (double[] row : rows) {
                cards.add(e.movieCard((int) row[0], json("score", row[1])));
            }
            step.put("items", cards);
        }
        return json("sweep", sweep, "cf_model", cfModel);
    }

    @GetMapping("/hybrid/compare")
    @SuppressWarnings("unchecked")
    public Map<String, Object> hybridCompare(@RequestParam("user_id") int userId,
                                             @RequestParam(defaultValue = "10") int n,
                                             @RequestParam(name = "cf_model", defaultValue = "item-knn") String cfModel) {
        Map<Integer, Double> ratedPos = e.ratedByPos(userId);
        if (ratedPos.isEmpty()) {
            return json("lists", Map.of(), "overlap", Map.of());
        }
        Map<String, Object> result = e.hybrid.compareSources(e.userRow(userId), ratedPos, n, cfModel);
        Map<String, List<Integer>> lists = (Map<String, List<Integer>>) result.get("lists");
        Set<Integer> all = new TreeSet<>();
        lists.values().forEach(all::addAll);
        e.hydratePosters(all);
        Map<String, Object> cardLists = new LinkedHashMap<>();
        lists.forEach((label, ids) -> cardLists.put(label, cards(ids)));
        return json("lists", cardLists, "overlap", result.get("overlap"), "cf_model", cfModel);
    }

    // ---------------------------------------------- comparison across viewers
    /** Same algorithm, several users: shows personalisation is real. */
    @GetMapping("/compare/users")
    public Map<String, Object> compareUsers(@RequestParam("user_ids") String userIds,
                                            @RequestParam(defaultValue = "hybrid") String method,
                                            @RequestParam(defaultValue = "6") int n) {
        List<Integer> ids = new ArrayList<>();
        try {
            for (String part : userIds.split(",")) {
                if (!part.isBlank()) {
                    ids.add(Integer.valueOf(part.strip()));
                }
            }
        } catch (NumberFormatException bad) {
            throw error(HttpStatus.UNPROCESSABLE_ENTITY, "user_ids must be comma-separated integers");
        }

        List<Map<String, Object>> columns = new ArrayList<>();
        List<Set<Integer>> allLists = new ArrayList<>();
        for (int userId : ids.subList(0, Math.min(4, ids.size()))) {
            Map<Integer, Double> ratedPos = e.ratedByPos(userId);
            if (ratedPos.isEmpty()) {
                continue;
            }
            Integer u = e.userRow(userId);
            List<Integer> movieIds = new ArrayList<>();
            List<Double> scores = new ArrayList<>();
            if (method.equals("content")) {
                for (ContentBasedRecommender.Scored row : e.content().recommend(e.ratedMap(userId), n).items()) {
                    movieIds.add(row.movieId());
                    scores.add(row.score());
                }
            } else if (method.equals("collaborative")) {
                for (Cf.Rec row : e.itemKnn.recommend(u, u != null ? null : ratedPos, n)) {
                    movieIds.add(e.data.itemIds[row.pos()]);
                    scores.add(row.rank());
                }
            } else {
                for (HybridRecommender.Item row : e.hybrid.recommend(u, ratedPos, n, "weighted", "item-knn", null).items()) {
                    movieIds.add(row.movieId());
                    scores.add(row.score());
                }
            }
            e.hydratePosters(movieIds);

            String custom = e.isCustom(userId) ? e.customName(userId) : null;
            List<Map<String, Object>> genreRows = e.content().genreProfile(e.ratedMap(userId));
            List<Map<String, Object>> items = new ArrayList<>();
            for (int i = 0; i < movieIds.size(); i++) {
                items.add(e.movieCard(movieIds.get(i), json("score", round(scores.get(i), 4))));
            }
            columns.add(json(
                    "userId", userId,
                    "name", custom != null ? custom : "User " + userId,
                    "n_ratings", ratedPos.size(),
                    "top_genres", new ArrayList<>(genreRows.subList(0, Math.min(3, genreRows.size()))),
                    "items", items));
            allLists.add(new HashSet<>(movieIds));
        }

        Set<Integer> shared = new TreeSet<>();
        if (allLists.size() > 1) {
            shared.addAll(allLists.get(0));
            allLists.forEach(shared::retainAll);
        }
        return json("columns", columns, "method", method, "shared", shared);
    }

    @GetMapping("/evaluation")
    public Map<String, Object> evaluation(@RequestParam(defaultValue = "10") int k,
                                          @RequestParam(defaultValue = "false") boolean force) {
        return e.evaluation(k, force);
    }

    // ------------------------------------------------------------------ browse
    private static final String CONTENT_REASON = "Shares terms with what you rated highest";
    private static final String COLLAB_REASON = "Rated alike by viewers nearest you";

    /** The streaming front page, one shelf per algorithm. */
    @GetMapping("/browse")
    public Map<String, Object> browse(@RequestParam("user_id") int userId, @RequestParam(required = false) Double alpha) {
        Map<Integer, Double> ratedPos = e.ratedByPos(userId);
        if (ratedPos.isEmpty()) {
            return json("empty", true, "message", "Rate a few films first - every shelf here is a recommender.");
        }
        Integer u = e.userRow(userId);
        double usedAlpha = alpha != null ? alpha : e.config.number("hybrid", "alpha");
        List<HybridRecommender.Item> items =
                e.hybrid.recommend(u, ratedPos, 10, "weighted", "item-knn", usedAlpha).items();
        if (items.isEmpty()) {
            return json("empty", true, "message", "Not enough signal yet - rate a few more films.");
        }

        Map<Integer, Double> ratedMap = e.ratedMap(userId);
        ContentBasedRecommender contentModel = e.content();
        List<ContentBasedRecommender.Scored> content = contentModel.recommend(ratedMap, 12).items();
        List<Cf.Rec> collab = e.itemKnn.recommend(u, u != null ? null : ratedPos, 12);

        // long tail: what only the content model can reach, minus anything the main content shelf
        // already shows
        Set<Integer> shown = new HashSet<>();
        content.forEach(row -> shown.add(row.movieId()));
        List<ContentBasedRecommender.Scored> cold = new ArrayList<>();
        for (ContentBasedRecommender.Scored row : contentModel.recommend(ratedMap, 120).items()) {
            if (cold.size() < 12 && popularity(row.movieId()) <= 12 && !shown.contains(row.movieId())) {
                cold.add(row);
            }
        }

        int[] topPos = Num.topN(toDouble(e.data.ratingMatrix.columnCounts()), 10);
        List<Integer> topIds = new ArrayList<>();
        for (int pos : topPos) {
            topIds.add(e.data.itemIds[pos]);
        }

        List<Integer> allIds = new ArrayList<>();
        items.forEach(it -> allIds.add(it.movieId()));
        content.forEach(row -> allIds.add(row.movieId()));
        collab.forEach(row -> allIds.add(e.data.itemIds[row.pos()]));
        cold.forEach(row -> allIds.add(row.movieId()));
        allIds.addAll(topIds);
        e.hydratePosters(Engine.distinct(allIds));

        HybridRecommender.Item heroItem = items.get(0);
        Map<String, Object> hero = e.movieCard(heroItem.movieId(), json(
                "content_raw", round(heroItem.contentRaw(), 4),
                "cf_rating", round(heroItem.cfRating(), 2),
                "score", round(heroItem.score(), 4),
                "lead", heroItem.lead()));

        List<Map<String, Object>> contentShelf = new ArrayList<>();
        for (ContentBasedRecommender.Scored row : content) {
            contentShelf.add(shelfCard(row.movieId(), row.score(), CONTENT_REASON, "content"));
        }
        List<Map<String, Object>> collabShelf = new ArrayList<>();
        for (Cf.Rec row : collab) {
            collabShelf.add(shelfCard(e.data.itemIds[row.pos()], row.predicted(), COLLAB_REASON, "collab"));
        }
        List<Map<String, Object>> coldShelf = new ArrayList<>();
        for (ContentBasedRecommender.Scored row : cold) {
            String reason = "Only " + popularity(row.movieId()) + " ratings - matched on description alone";
            coldShelf.add(shelfCard(row.movieId(), row.score(), reason, "cold"));
        }

        List<Map<String, Object>> topTen = new ArrayList<>();
        for (int i = 0; i < topIds.size(); i++) {
            topTen.add(e.movieCard(topIds.get(i), json("rank", i + 1, "n", popularity(topIds.get(i)))));
        }
        List<Map<String, Object>> reel = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            HybridRecommender.Item it = items.get(i);
            reel.add(e.movieCard(it.movieId(), json(
                    "rank", i + 1,
                    "score", round(it.score(), 4),
                    "content_raw", round(it.contentRaw(), 4),
                    "content_norm", round(it.contentNorm(), 4),
                    "cf_raw", round(it.cfRaw(), 4),
                    "cf_norm", round(it.cfNorm(), 4),
                    "cf_rating", round(it.cfRating(), 2),
                    "content_part", round(it.contentPart(), 4),
                    "cf_part", round(it.cfPart(), 4),
                    "lead", it.lead())));
        }

        return json(
                "hero", hero,
                "alpha", usedAlpha,
                "rails", List.of(
                        json("key", "content", "kicker", "Content-based - reads the film",
                                "title", "Because of what you rated highest", "tone", "content",
                                "films", contentShelf),
                        json("key", "collab", "kicker", "Collaborative - reads the crowd",
                                "title", "Viewers who rate like you also loved", "tone", "collaborative",
                                "films", collabShelf),
                        json("key", "cold", "kicker", "The long tail - collaborative filtering is blind here",
                                "title", "Almost nobody has rated these", "tone", "content",
                                "films", coldShelf)),
                "top_ten", topTen,
                "reel", reel);
    }

    private int popularity(int movieId) {
        Movie movie = e.data.movie(movieId);
        return movie == null ? 0 : movie.nRatings();
    }

    private Map<String, Object> shelfCard(int movieId, double score, String reason, String mode) {
        return e.movieCard(movieId, json("score", round(score, 4), "reason", reason, "mode", mode));
    }

    private static double[] toDouble(int[] values) {
        return Arrays.stream(values).asDoubleStream().toArray();
    }

    // ------------------------------------------------------------------ matrix
    /** A corner of the rating matrix: the thing every CF model actually sees. */
    @GetMapping("/matrix")
    public Map<String, Object> matrix(@RequestParam(name = "user_id", required = false) Integer userId,
                                      @RequestParam(defaultValue = "26") int users,
                                      @RequestParam(defaultValue = "40") int items) {
        CsrMatrix R = e.data.ratingMatrix;
        int[] topItems = Num.topN(toDouble(R.columnCounts()), items);
        int[] counts = new int[R.nRows];
        for (int row = 0; row < R.nRows; row++) {
            counts[row] = R.rowLength(row);
        }
        List<Integer> topUsers = new ArrayList<>();
        for (int row : Num.topN(toDouble(counts), users)) {
            topUsers.add(row);
        }
        if (userId != null && !e.isCustom(userId)) {
            Integer u = e.userRow(userId);
            if (u != null && !topUsers.contains(u)) {
                List<Integer> withViewer = new ArrayList<>();
                withViewer.add(u);
                withViewer.addAll(topUsers.subList(0, Math.max(0, Math.min(users - 1, topUsers.size()))));
                topUsers = withViewer;
            }
        }

        List<Map<String, Object>> userRows = new ArrayList<>();
        List<List<Double>> values = new ArrayList<>();
        int stored = 0;
        for (int u : topUsers) {
            int id = e.data.userIds[u];
            userRows.add(json("userId", id, "pos", u, "highlight", userId != null && id == userId));
            List<Double> row = new ArrayList<>(topItems.length);
            for (int item : topItems) {
                double value = R.get(u, item);
                if (value > 0) {
                    stored++;
                }
                row.add(round(value, 1));
            }
            values.add(row);
        }
        List<Map<String, Object>> itemRows = new ArrayList<>();
        for (int item : topItems) {
            int id = e.data.itemIds[item];
            itemRows.add(json("movieId", id, "title", e.data.title(id)));
        }
        double cells = (double) topUsers.size() * topItems.length;
        return json(
                "users", userRows,
                "items", itemRows,
                "values", values,
                "density", round(stored / cells * 100, 1),
                "full_density", round(R.nnz() / ((double) R.nRows * R.nCols) * 100, 2));
    }
}
