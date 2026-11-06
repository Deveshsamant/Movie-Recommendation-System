package com.movierec.dataset;

import java.util.List;

/** One row of the tidy movie catalogue. year and tmdbId are null when MovieLens has none. */
public record Movie(
        int movieId,
        String title,
        String cleanTitle,
        Integer year,
        List<String> genres,
        List<String> tags,
        Long tmdbId,
        int nRatings,
        double avgRating) {}
