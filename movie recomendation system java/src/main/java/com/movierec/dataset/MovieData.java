package com.movierec.dataset;

import static com.movierec.math.Num.round;

import com.movierec.math.CsrMatrix;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** Everything the recommenders and the API need, loaded once. */
public final class MovieData {

    /** Sorted by movieId, so a movie's array index is its item position in every matrix. */
    public final Movie[] movies;
    public final int[] userIds;
    public final int[] itemIds;
    public final Map<Integer, Integer> userPos;
    public final Map<Integer, Integer> itemPos;
    public final CsrMatrix ratingMatrix;
    public final CsrMatrix train;
    public final Map<Integer, Map<Integer, Double>> testByUser;
    public final List<String> allGenres;

    private final String datasetName;
    private final int ratingCount;
    private final double meanRating;
    private final Map<Integer, Movie> byId;

    MovieData(String datasetName, Movie[] movies, int[] userIds, int[] itemIds,
              Map<Integer, Integer> userPos, Map<Integer, Integer> itemPos,
              CsrMatrix ratingMatrix, CsrMatrix train, Map<Integer, Map<Integer, Double>> testByUser,
              int ratingCount, double meanRating) {
        this.datasetName = datasetName;
        this.movies = movies;
        this.userIds = userIds;
        this.itemIds = itemIds;
        this.userPos = userPos;
        this.itemPos = itemPos;
        this.ratingMatrix = ratingMatrix;
        this.train = train;
        this.testByUser = testByUser;
        this.ratingCount = ratingCount;
        this.meanRating = meanRating;

        byId = new java.util.HashMap<>();
        TreeSet<String> genres = new TreeSet<>();
        for (Movie movie : movies) {
            byId.put(movie.movieId(), movie);
            genres.addAll(movie.genres());
        }
        allGenres = new ArrayList<>(genres);
    }

    public int nUsers() {
        return userIds.length;
    }

    public int nItems() {
        return itemIds.length;
    }

    public Movie movie(int movieId) {
        return byId.get(movieId);
    }

    public String title(int movieId) {
        Movie movie = byId.get(movieId);
        return movie != null ? movie.title() : "movie " + movieId;
    }

    public Map<String, Object> stats() {
        double density = ratingCount / (double) (nUsers() * (long) nItems());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dataset", datasetName);
        out.put("n_users", nUsers());
        out.put("n_items", nItems());
        out.put("n_ratings", ratingCount);
        out.put("density", round(density * 100, 3));
        out.put("mean_rating", round(meanRating, 3));
        out.put("rating_scale", List.of(0.5, 5.0));
        out.put("n_genres", allGenres.size());
        return out;
    }
}
