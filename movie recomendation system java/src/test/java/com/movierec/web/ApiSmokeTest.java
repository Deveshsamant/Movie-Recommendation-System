package com.movierec.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** Boots the whole app on a tiny dataset written to a temp folder: no download, no TMDB. */
@SpringBootTest
@AutoConfigureMockMvc
class ApiSmokeTest {

    static {
        try {
            System.setProperty("app.home", writeFixture().toString());
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static Path writeFixture() throws IOException {
        Path home = Files.createTempDirectory("movierec-test");
        Path data = Files.createDirectories(home.resolve("data/ml-latest-small"));
        Files.writeString(home.resolve("config.json"), """
                {"tmdb_api_key": "", "posters_enabled": false,
                 "svd": {"n_factors": 4, "n_epochs": 3, "lr": 0.007, "reg": 0.02, "seed": 42},
                 "knn": {"k_neighbors": 5, "shrinkage": 2},
                 "hybrid": {"alpha": 0.7, "cf_model": "item-knn", "cold_start_threshold": 8}}
                """);

        StringBuilder movies = new StringBuilder("movieId,title,genres\n");
        StringBuilder links = new StringBuilder("movieId,imdbId,tmdbId\n");
        String[] genres = {"Action|Sci-Fi", "Comedy", "Drama|Romance", "Comedy|Romance", "(no genres listed)"};
        for (int m = 1; m <= 40; m++) {
            String title = m == 1 ? "\"Heist, The (2001)\"" : "Film Number " + m + " (" + (1990 + m % 20) + ")";
            movies.append(m).append(',').append(title).append(',').append(genres[m % genres.length]).append('\n');
            links.append(m).append(",1,").append(m == 2 ? "" : String.valueOf(1000 + m)).append('\n');
        }
        StringBuilder ratings = new StringBuilder("userId,movieId,rating,timestamp\n");
        int t = 0;
        for (int u = 1; u <= 20; u++) {
            for (int m = 1; m <= 40; m++) {
                if ((u * 7 + m * 3) % 4 != 0) {
                    double rating = 0.5 + ((u + m * m) % 10) * 0.5;
                    ratings.append(u).append(',').append(m).append(',').append(rating).append(',').append(++t).append('\n');
                }
            }
        }
        Files.writeString(data.resolve("movies.csv"), movies);
        Files.writeString(data.resolve("links.csv"), links);
        Files.writeString(data.resolve("ratings.csv"), ratings);
        Files.writeString(data.resolve("tags.csv"), "userId,movieId,tag,timestamp\n1,1,Bank Job,1\n2,3,Bank Job,2\n");
        return home;
    }

    @Autowired
    private MockMvc mvc;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void statusDescribesTheDataAndModels() throws Exception {
        mvc.perform(get("/api/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dataset.n_users").value(20))
                .andExpect(jsonPath("$.dataset.n_items").value(40))
                .andExpect(jsonPath("$.models.svd.n_factors").value(4))
                .andExpect(jsonPath("$.tmdb.available").value(false));
    }

    @Test
    @SuppressWarnings("unchecked")
    void overviewSummarisesTheRatings() throws Exception {
        String json = mvc.perform(get("/api/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rating_histogram", hasSize(10)))
                .andExpect(jsonPath("$.genres[0].ratings").isNumber())
                .andExpect(jsonPath("$.decades.length()", greaterThan(0)))
                .andExpect(jsonPath("$.long_tail.curve[0].ratings_pct").value(0.0))
                .andExpect(jsonPath("$.long_tail.curve[50].films_pct").value(100))
                .andExpect(jsonPath("$.long_tail.curve[50].ratings_pct").value(100.0))
                .andReturn().getResponse().getContentAsString();

        Map<String, Object> overview = mapper.readValue(json, Map.class);
        int fromHistogram = ((java.util.List<Map<String, Object>>) overview.get("rating_histogram")).stream()
                .mapToInt(row -> (int) row.get("count"))
                .sum();
        Map<String, Object> sparsity = (Map<String, Object>) overview.get("sparsity");
        Map<String, Object> split = (Map<String, Object>) overview.get("split");
        assertEquals(sparsity.get("filled_cells"), fromHistogram);
        assertEquals(fromHistogram, (int) split.get("train_ratings") + (int) split.get("test_ratings"));
    }

    @Test
    void catalogueEndpoints() throws Exception {
        mvc.perform(get("/api/genres")).andExpect(jsonPath("$.genres", hasSize(5)));
        mvc.perform(get("/api/movies/1"))
                .andExpect(jsonPath("$.title").value("Heist, The"))
                .andExpect(jsonPath("$.year").value(2001));
        mvc.perform(get("/api/movies/2")).andExpect(jsonPath("$.tmdbId").doesNotExist());
        mvc.perform(get("/api/movies/search").param("q", "bank job"))
                .andExpect(jsonPath("$.results[0].movieId").value(1));
        mvc.perform(get("/api/movies/popular").param("limit", "5").param("min_ratings", "1"))
                .andExpect(jsonPath("$.results", hasSize(5)));
        mvc.perform(get("/api/movies/sampler").param("limit", "3")).andExpect(status().isOk());
    }

    @Test
    void everyRecommenderAnswersWithItsArithmetic() throws Exception {
        mvc.perform(get("/api/recommend/content").param("user_id", "1"))
                .andExpect(jsonPath("$.items.length()", greaterThan(0)))
                .andExpect(jsonPath("$.items[0].explanation.because_of").isArray());
        for (String model : new String[] {"item-knn", "user-knn", "svd"}) {
            mvc.perform(get("/api/recommend/collaborative").param("user_id", "1").param("model", model))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items[0].explanation").exists());
        }
        for (String strategy : new String[] {"weighted", "switching", "rank", "cascade"}) {
            mvc.perform(get("/api/recommend/hybrid").param("user_id", "1").param("strategy", strategy))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.trace.strategy").value(strategy))
                    .andExpect(jsonPath("$.items.length()", greaterThan(0)));
        }
    }

    @Test
    void analysisEndpoints() throws Exception {
        mvc.perform(get("/api/browse").param("user_id", "1"))
                .andExpect(jsonPath("$.rails", hasSize(3)))
                .andExpect(jsonPath("$.top_ten", hasSize(10)));
        mvc.perform(get("/api/neighbours").param("user_id", "1")).andExpect(status().isOk());
        mvc.perform(get("/api/svd/space").param("user_id", "1"))
                .andExpect(jsonPath("$.points.length()", greaterThan(0)))
                .andExpect(jsonPath("$.explained", hasSize(3)));
        mvc.perform(get("/api/svd/factors").param("k", "1")).andExpect(jsonPath("$.positive.length()", greaterThan(0)));
        mvc.perform(get("/api/hybrid/alpha-sweep").param("user_id", "1")).andExpect(jsonPath("$.sweep", hasSize(5)));
        mvc.perform(get("/api/hybrid/compare").param("user_id", "1")).andExpect(jsonPath("$.overlap").exists());
        mvc.perform(get("/api/compare/users").param("user_ids", "1,2,3")).andExpect(jsonPath("$.columns", hasSize(3)));
        mvc.perform(get("/api/matrix").param("user_id", "1")).andExpect(jsonPath("$.users[0].userId").exists());
        mvc.perform(get("/api/evaluation"))
                .andExpect(jsonPath("$.rows", hasSize(7)))
                .andExpect(jsonPath("$.rows[0].rmse").isNumber());
    }

    @Test
    @SuppressWarnings("unchecked")
    void customViewerLifecycle() throws Exception {
        String created = mvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"Asha\", \"ratings\": {\"1\": 5, \"3\": 2.5}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Asha"))
                .andExpect(jsonPath("$.ratings.1").value(5.0))
                .andReturn().getResponse().getContentAsString();
        int id = (int) mapper.readValue(created, Map.class).get("userId");
        assertEquals(true, id >= 900000);

        for (int movie : new int[] {4, 5, 6}) {
            mvc.perform(post("/api/users/" + id + "/ratings").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"movieId\": " + movie + ", \"rating\": 4}"))
                    .andExpect(jsonPath("$.readiness.collaborative").value(true));
        }
        // a viewer outside the training matrix is folded into the SVD and matched by ratings alone
        mvc.perform(get("/api/recommend/collaborative").param("user_id", "" + id).param("model", "svd"))
                .andExpect(jsonPath("$.method.fold_in.n_factors").value(4));
        mvc.perform(get("/api/recommend/hybrid").param("user_id", "" + id).param("strategy", "switching"))
                .andExpect(jsonPath("$.trace.cold_start").value(true));
        mvc.perform(patch("/api/users/" + id).contentType(MediaType.APPLICATION_JSON).content("{\"name\": \" Ravi \"}"))
                .andExpect(jsonPath("$.name").value("Ravi"));
        mvc.perform(post("/api/users/" + id + "/ratings").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"movieId\": 6, \"rating\": null}"))
                .andExpect(jsonPath("$.n_ratings").value(4));
        mvc.perform(delete("/api/users/" + id)).andExpect(jsonPath("$.deleted").value(true));
        mvc.perform(delete("/api/users/" + id)).andExpect(jsonPath("$.deleted").value(false));
    }

    @Test
    void viewerWithNoRatingsGetsAMessageNotAnError() throws Exception {
        mvc.perform(get("/api/recommend/content").param("user_id", "999"))
                .andExpect(jsonPath("$.empty").value(true));
        mvc.perform(get("/api/browse").param("user_id", "999")).andExpect(jsonPath("$.empty").value(true));
        mvc.perform(get("/api/explain/content").param("user_id", "999").param("movie_id", "1"))
                .andExpect(jsonPath("$.explanation.score").value(0.0));
    }

    @Test
    void errorsKeepFastApiStatusCodes() throws Exception {
        mvc.perform(get("/api/movies/99999")).andExpect(status().isNotFound()).andExpect(jsonPath("$.detail").value("not found"));
        mvc.perform(get("/api/nope")).andExpect(status().isNotFound()).andExpect(jsonPath("$.detail").value("not found"));
        mvc.perform(get("/api/recommend/content")).andExpect(status().isUnprocessableEntity());
        mvc.perform(get("/api/recommend/content").param("user_id", "abc")).andExpect(status().isUnprocessableEntity());
        mvc.perform(get("/api/movies/search").param("q", "")).andExpect(status().isUnprocessableEntity());
        mvc.perform(get("/api/svd/factors").param("k", "99")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/genres")).andExpect(status().isMethodNotAllowed());
    }

    @Test
    void servesTheFrontEnd() throws Exception {
        mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Movie Recommender System")))
                .andExpect(header().string("Cache-Control", "no-cache, must-revalidate"));
        mvc.perform(get("/static/js/app.js"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-cache, must-revalidate"));
        // an unknown page falls back to the single-page app, as under FastAPI
        mvc.perform(get("/some/page")).andExpect(status().isOk()).andExpect(content().string(containsString("<!DOCTYPE html>")));
    }
}
