package com.movierec.tmdb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TmdbClientTest {

    private static TmdbClient.RawResponse parse(String raw) throws IOException {
        return TmdbClient.parseHttpResponse(raw.getBytes(StandardCharsets.ISO_8859_1));
    }

    private static String body(TmdbClient.RawResponse response) {
        return new String(response.body(), StandardCharsets.ISO_8859_1);
    }

    @Test
    void parsesAPlainResponse() throws IOException {
        TmdbClient.RawResponse response = parse("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n{}");
        assertEquals(200, response.status());
        assertEquals("{}", body(response));
    }

    @Test
    void decodesAChunkedResponse() throws IOException {
        TmdbClient.RawResponse response = parse(
                "HTTP/1.1 401 Unauthorized\r\nTransfer-Encoding: chunked\r\n\r\n4\r\nabcd\r\n3;ext=1\r\nefg\r\n0\r\n\r\n");
        assertEquals(401, response.status());
        assertEquals("abcdefg", body(response));
    }

    @Test
    void rejectsATruncatedResponse() {
        assertThrows(IOException.class, () -> parse("HTTP/1.1 200 OK\r\nContent-Length: 2"));
        assertThrows(IOException.class, () -> parse("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\nff\r\nabc"));
    }

    @Test
    void distilsAMovie() {
        Map<String, Object> out = TmdbClient.distil(Map.of(
                "id", 550,
                "poster_path", "/p.jpg",
                "overview", "Rules.",
                "runtime", 139,
                "genres", List.of(Map.of("name", "Drama")),
                "credits", Map.of(
                        "cast", List.of(Map.of("name", "Edward Norton")),
                        "crew", List.of(
                                Map.of("name", "A Writer", "job", "Writer"),
                                Map.of("name", "David Fincher", "job", "Director"))),
                "keywords", Map.of("keywords", List.of(Map.of("name", "fight")))),
                "movie");
        assertEquals(550, out.get("tmdb_id"));
        assertEquals("https://image.tmdb.org/t/p/w342/p.jpg", out.get("poster"));
        assertNull(out.get("backdrop"));
        assertEquals("", out.get("tagline"));
        assertEquals(List.of("David Fincher"), out.get("directors"));
        assertEquals(List.of("Edward Norton"), out.get("cast"));
        assertEquals(List.of("fight"), out.get("keywords"));
        assertEquals(List.of("Drama"), out.get("tmdb_genres"));
    }

    /** Miniseries only exist in TMDB's television namespace, which names its fields differently. */
    @Test
    void distilsATelevisionShow() {
        Map<String, Object> out = TmdbClient.distil(Map.of(
                "id", 1,
                "first_air_date", "2013-03-17",
                "episode_run_time", List.of(90),
                "created_by", List.of(Map.of("name", "A Creator")),
                "keywords", Map.of("results", List.of(Map.of("name", "war")))),
                "tv");
        assertEquals("tv", out.get("kind"));
        assertEquals("2013-03-17", out.get("release_date"));
        assertEquals(90, out.get("runtime"));
        assertEquals(List.of("A Creator"), out.get("directors"));
        assertEquals(List.of("war"), out.get("keywords"));
    }
}
