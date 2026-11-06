package com.movierec.tmdb;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.movierec.config.AppConfig;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;

/**
 * TMDB client with automatic route failover, disk cache and graceful degradation.
 *
 * <p>Some ISPs (notably several in India) DNS-poison api.themoviedb.org while leaving the image
 * CDN and the legacy api.tmdb.org alias reachable. This client probes the available routes once,
 * remembers the winner, and keeps working even when every route fails: callers simply get no
 * posters.
 */
public final class TmdbClient {

    /** A film to fetch. The title and year let TMDB be searched by name when the id is dead. */
    public record Target(Long tmdbId, String title, Integer year) {}

    private static final String IMAGE_BASE = "https://image.tmdb.org/t/p/";
    private static final String POSTER_SIZE = "w342";
    private static final String BACKDROP_SIZE = "w780";
    private static final String STATUS_KEY = "__status__";
    private static final String MISSING_KEY = "__missing__";

    private final AppConfig config;
    private final Path cachePath;
    private final Path routePath;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(8))
            .build();

    private final Object lock = new Object();
    private Map<String, Map<String, Object>> memory = new LinkedHashMap<>();
    private boolean dirty;
    private Map<String, Object> route;

    public TmdbClient(AppConfig config) {
        this.config = config;
        this.cachePath = config.cacheDir.resolve("tmdb_cache.json");
        this.routePath = config.cacheDir.resolve("tmdb_route.json");
        loadCache();
    }

    private static double now() {
        return System.currentTimeMillis() / 1000.0;
    }

    // ------------------------------------------------------------------- cache
    private void loadCache() {
        if (!Files.exists(cachePath)) {
            return;
        }
        try {
            memory = mapper.readValue(cachePath.toFile(), new TypeReference<LinkedHashMap<String, Map<String, Object>>>() {});
        } catch (IOException e) {
            memory = new LinkedHashMap<>();
        }
    }

    public void flushCache() {
        synchronized (lock) {
            if (!dirty) {
                return;
            }
            try {
                Path tmp = cachePath.resolveSibling("tmdb_cache.tmp");
                Files.write(tmp, mapper.writeValueAsBytes(memory));
                Files.move(tmp, cachePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                dirty = false;
            } catch (IOException e) {
                System.err.println("could not save the TMDB cache: " + e.getMessage());
            }
        }
    }

    // ----------------------------------------------------------------- routing
    /** Resolves a hostname through DNS-over-HTTPS, bypassing a poisoned resolver. */
    private List<String> dohResolve(String host) {
        String[] providers = {"https://cloudflare-dns.com/dns-query", "https://dns.google/resolve"};
        for (String provider : providers) {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(provider + "?name=" + host + "&type=A"))
                        .timeout(Duration.ofSeconds(8))
                        .header("accept", "application/dns-json")
                        .build();
                HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
                Map<String, Object> body = mapper.readValue(response.body(), new TypeReference<>() {});
                List<String> ips = new ArrayList<>();
                for (Map<String, Object> answer : mapList(body.get("Answer"))) {
                    if (Integer.valueOf(1).equals(answer.get("type"))) {
                        ips.add(String.valueOf(answer.get("data")));
                    }
                }
                if (!ips.isEmpty()) {
                    return ips;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return List.of();
            } catch (Exception ignored) {
                // try the next provider
            }
        }
        return List.of();
    }

    private List<Map<String, Object>> candidateRoutes() {
        List<Map<String, Object>> routes = new ArrayList<>();
        routes.add(new LinkedHashMap<>(Map.of("kind", "host", "host", "api.themoviedb.org")));
        routes.add(new LinkedHashMap<>(Map.of("kind", "host", "host", "api.tmdb.org")));
        for (String ip : dohResolve("api.themoviedb.org").stream().limit(2).toList()) {
            Map<String, Object> direct = new LinkedHashMap<>();
            direct.put("kind", "ip");
            direct.put("host", "api.themoviedb.org");
            direct.put("ip", ip);
            routes.add(direct);
        }
        return routes;
    }

    /** One GET against a specific route: parsed JSON, or null for a server error. */
    private Map<String, Object> request(Map<String, Object> via, String path, Map<String, String> params, int timeoutSeconds)
            throws IOException, InterruptedException {
        Map<String, String> query = new LinkedHashMap<>(params);
        query.put("api_key", config.string("tmdb_api_key"));
        String encoded = query.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        String pathAndQuery = "/3" + path + "?" + encoded;

        int status;
        byte[] body;
        if ("host".equals(via.get("kind"))) {
            HttpRequest request = HttpRequest.newBuilder(URI.create("https://" + via.get("host") + pathAndQuery))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .build();
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            status = response.statusCode();
            body = response.body();
        } else {
            RawResponse response = getViaIp((String) via.get("ip"), (String) via.get("host"), pathAndQuery, timeoutSeconds);
            status = response.status();
            body = response.body();
        }

        if (status == 401 || status == 404) {
            Map<String, Object> marker = new LinkedHashMap<>();
            marker.put(STATUS_KEY, status);
            return marker;
        }
        return status == 200 ? mapper.readValue(body, new TypeReference<>() {}) : null;
    }

    record RawResponse(int status, byte[] body) {}

    /**
     * Direct-IP route. Connects to the address DoH returned, yet presents the real hostname as SNI
     * and verifies the certificate against that hostname, so TLS stays fully checked. The JDK's
     * HttpClient has no DNS override, hence the hand-written HTTP/1.1 exchange.
     */
    private RawResponse getViaIp(String ip, String host, String pathAndQuery, int timeoutSeconds) throws IOException {
        int millis = timeoutSeconds * 1000;
        try (Socket raw = new Socket()) {
            raw.connect(new InetSocketAddress(ip, 443), millis);
            raw.setSoTimeout(millis);
            SSLContext context = SSLContext.getDefault();
            try (SSLSocket tls = (SSLSocket) context.getSocketFactory().createSocket(raw, host, 443, true)) {
                SSLParameters parameters = tls.getSSLParameters();
                parameters.setServerNames(List.of(new SNIHostName(host)));
                parameters.setEndpointIdentificationAlgorithm("HTTPS");
                tls.setSSLParameters(parameters);
                tls.startHandshake();

                OutputStream out = tls.getOutputStream();
                String head = "GET " + pathAndQuery + " HTTP/1.1\r\nHost: " + host
                        + "\r\nAccept: application/json\r\nConnection: close\r\n\r\n";
                out.write(head.getBytes(StandardCharsets.US_ASCII));
                out.flush();
                return parseHttpResponse(tls.getInputStream().readAllBytes());
            }
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    static RawResponse parseHttpResponse(byte[] response) throws IOException {
        int split = indexOf(response, new byte[] {'\r', '\n', '\r', '\n'}, 0);
        if (split < 0) {
            throw new IOException("truncated HTTP response");
        }
        String head = new String(response, 0, split, StandardCharsets.ISO_8859_1);
        String[] lines = head.split("\r\n");
        String[] statusParts = lines[0].split(" ");
        if (statusParts.length < 2) {
            throw new IOException("malformed HTTP status line");
        }
        int status = Integer.parseInt(statusParts[1]);
        boolean chunked = false;
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].toLowerCase(Locale.ROOT);
            if (line.startsWith("transfer-encoding:") && line.contains("chunked")) {
                chunked = true;
            }
        }
        int start = split + 4;
        if (!chunked) {
            byte[] body = new byte[response.length - start];
            System.arraycopy(response, start, body, 0, body.length);
            return new RawResponse(status, body);
        }
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        int at = start;
        while (true) {
            int lineEnd = indexOf(response, new byte[] {'\r', '\n'}, at);
            if (lineEnd < 0) {
                throw new IOException("truncated chunked body");
            }
            String sizeText = new String(response, at, lineEnd - at, StandardCharsets.ISO_8859_1);
            int semicolon = sizeText.indexOf(';');
            int size = Integer.parseInt((semicolon >= 0 ? sizeText.substring(0, semicolon) : sizeText).strip(), 16);
            if (size == 0) {
                return new RawResponse(status, body.toByteArray());
            }
            if (lineEnd + 2 + size > response.length) {
                throw new IOException("truncated chunked body");
            }
            body.write(response, lineEnd + 2, size);
            at = lineEnd + 2 + size + 2;
        }
    }

    private static int indexOf(byte[] haystack, byte[] needle, int from) {
        outer:
        for (int i = from; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    /** Finds (once) a working route to the TMDB API and remembers it for a day. */
    public Map<String, Object> getRoute() {
        synchronized (lock) {
            if (route != null) {
                return route;
            }
            if (Files.exists(routePath)) {
                try {
                    Map<String, Object> cached = mapper.readValue(routePath.toFile(), new TypeReference<>() {});
                    Object checkedAt = cached.get("checked_at");
                    if (checkedAt instanceof Number number && now() - number.doubleValue() < 86400) {
                        route = cached;
                        return route;
                    }
                } catch (IOException ignored) {
                    // probe again
                }
            }
        }

        Map<String, Object> found = new LinkedHashMap<>();
        found.put("kind", "none");
        if (config.string("tmdb_api_key").isEmpty() || !config.flag("posters_enabled")) {
            found.put("reason", "posters disabled or API key missing");
        } else {
            found.put("reason", "no reachable TMDB route");
            for (Map<String, Object> candidate : candidateRoutes()) {
                Map<String, Object> probe;
                try {
                    probe = request(candidate, "/movie/550", Map.of(), 8);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    continue;
                }
                if (probe == null) {
                    continue;
                }
                if (Integer.valueOf(401).equals(probe.get(STATUS_KEY))) {
                    found.put("reason", "TMDB rejected the API key (401)");
                    break;
                }
                if (Integer.valueOf(550).equals(probe.get("id"))) {
                    found = new LinkedHashMap<>(candidate);
                    break;
                }
            }
        }

        found.put("checked_at", now());
        synchronized (lock) {
            route = found;
            try {
                Files.write(routePath, mapper.writeValueAsBytes(found));
            } catch (IOException ignored) {
                // the route is simply probed again next start
            }
            return route;
        }
    }

    public Map<String, Object> routeStatus() {
        Map<String, Object> current = getRoute();
        String kind = String.valueOf(current.get("kind"));
        String label = switch (kind) {
            case "host" -> "direct via " + current.get("host");
            case "ip" -> "DNS-over-HTTPS to " + current.get("ip");
            case "none" -> "unavailable";
            default -> "unknown";
        };
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("available", !"none".equals(kind));
        out.put("route", label);
        out.put("reason", current.get("reason"));
        out.put("cached_titles", cacheSize());
        return out;
    }

    private boolean routeUnavailable() {
        return "none".equals(getRoute().get("kind"));
    }

    // -------------------------------------------------------------- public API
    private static String imageUrl(Object path, String size) {
        return path instanceof String text && !text.isEmpty() ? IMAGE_BASE + size + text : null;
    }

    /**
     * Keeps only the fields the app actually uses.
     *
     * <p>Handles both /movie and /tv shapes: MovieLens contains a fair number of miniseries (Roots,
     * Generation War, Over the Garden Wall) that only exist in TMDB's television namespace.
     */
    static Map<String, Object> distil(Map<String, Object> payload, String kind) {
        Map<String, Object> credits = asMap(payload.get("credits"));
        List<Map<String, Object>> castRows = mapList(credits.get("cast"));
        List<String> cast = new ArrayList<>();
        for (Map<String, Object> person : castRows.subList(0, Math.min(8, castRows.size()))) {
            cast.add(nameOf(person));
        }
        List<String> directors = new ArrayList<>();
        for (Map<String, Object> person : mapList(credits.get("crew"))) {
            if ("Director".equals(person.get("job")) && directors.size() < 2) {
                directors.add(nameOf(person));
            }
        }
        if (directors.isEmpty()) { // television credits creators rather than directors
            for (Map<String, Object> person : mapList(payload.get("created_by"))) {
                if (directors.size() < 2) {
                    directors.add(nameOf(person));
                }
            }
        }

        Map<String, Object> keywordBlock = asMap(payload.get("keywords"));
        List<Map<String, Object>> keywordRows = mapList(keywordBlock.get("keywords"));
        if (keywordRows.isEmpty()) {
            keywordRows = mapList(keywordBlock.get("results"));
        }
        List<String> keywords = new ArrayList<>();
        for (Map<String, Object> keyword : keywordRows.subList(0, Math.min(20, keywordRows.size()))) {
            keywords.add(nameOf(keyword));
        }

        Object runtime = payload.get("runtime");
        if (runtime == null && payload.get("episode_run_time") instanceof List<?> episodes && !episodes.isEmpty()) {
            runtime = episodes.get(0);
        }
        List<String> genres = new ArrayList<>();
        for (Map<String, Object> genre : mapList(payload.get("genres"))) {
            genres.add(nameOf(genre));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tmdb_id", payload.get("id"));
        out.put("kind", kind);
        out.put("poster", imageUrl(payload.get("poster_path"), POSTER_SIZE));
        out.put("backdrop", imageUrl(payload.get("backdrop_path"), BACKDROP_SIZE));
        out.put("overview", textOf(payload.get("overview")));
        out.put("tagline", textOf(payload.get("tagline")));
        out.put("runtime", runtime);
        out.put("vote_average", payload.get("vote_average"));
        out.put("vote_count", payload.get("vote_count"));
        String released = textOf(payload.get("release_date"));
        out.put("release_date", released.isEmpty() ? textOf(payload.get("first_air_date")) : released);
        out.put("tmdb_genres", genres);
        out.put("cast", cast);
        out.put("directors", directors);
        out.put("keywords", keywords);
        out.put("fetched_at", now());
        return out;
    }

    private static String nameOf(Map<String, Object> row) {
        Object name = row.get("name");
        return name == null ? "" : name.toString();
    }

    private static String textOf(Object value) {
        return value == null ? "" : value.toString();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> mapList(Object value) {
        return value instanceof List ? (List<Map<String, Object>>) value : List.of();
    }

    /**
     * Recovers a film whose MovieLens tmdbId is dead.
     *
     * <p>links.csv was generated years ago; TMDB entries since get merged, deleted or re-issued, and
     * miniseries were never in the movie namespace at all. Searching by title (and year, when we
     * have one) finds most of them again.
     */
    private Map<String, Object> searchFallback(String title, Integer year) throws InterruptedException {
        if (title == null || title.isEmpty() || routeUnavailable()) {
            return null;
        }
        Map<String, Object> via = getRoute();

        record Attempt(String path, Map<String, String> params) {}
        List<Attempt> attempts = new ArrayList<>();
        if (year != null && year != 0) {
            attempts.add(new Attempt("/search/movie", Map.of("query", title, "year", year.toString())));
            attempts.add(new Attempt("/search/tv", Map.of("query", title, "first_air_date_year", year.toString())));
        }
        attempts.add(new Attempt("/search/movie", Map.of("query", title)));
        attempts.add(new Attempt("/search/tv", Map.of("query", title)));

        for (Attempt attempt : attempts) {
            Map<String, Object> found;
            try {
                found = request(via, attempt.path(), attempt.params(), 10);
            } catch (IOException e) {
                continue;
            }
            if (found == null || found.containsKey(STATUS_KEY)) {
                continue;
            }
            List<Map<String, Object>> results = mapList(found.get("results"));
            if (results.isEmpty()) {
                continue;
            }

            String kind = attempt.path().endsWith("/tv") ? "tv" : "movie";
            Map<String, Object> best = results.get(0);
            // Prefer a hit that actually has artwork.
            for (Map<String, Object> candidate : results.subList(0, Math.min(5, results.size()))) {
                if (imageUrl(candidate.get("poster_path"), "") != null) {
                    best = candidate;
                    break;
                }
            }
            Map<String, Object> full = null;
            try {
                full = request(via, "/" + kind + "/" + best.get("id"),
                        Map.of("append_to_response", "credits,keywords"), 12);
            } catch (IOException ignored) {
                // fall back to the search hit itself
            }
            if (full != null && !full.containsKey(STATUS_KEY)) {
                return distil(full, kind);
            }
            if (imageUrl(best.get("poster_path"), "") != null) {
                return distil(best, kind);
            }
        }
        return null;
    }

    /** Distilled metadata for one film, using the cache when possible. */
    public Map<String, Object> fetch(Target target, boolean retryMissing) throws InterruptedException {
        String title = target.title();
        if (target.tmdbId() == null) {
            // No id at all: title search is the only route.
            return title != null ? searchFallback(title, target.year()) : null;
        }
        String key = target.tmdbId().toString();

        Map<String, Object> hit;
        synchronized (lock) {
            hit = memory.get(key);
        }
        if (hit != null) {
            if (!isMissing(hit)) {
                return hit;
            }
            if (!(retryMissing && title != null && !title.isEmpty())) {
                return null; // known-bad, and no reason to look again
            }
        }
        if (routeUnavailable()) {
            return null;
        }

        Map<String, Object> payload;
        try {
            payload = request(getRoute(), "/movie/" + key, Map.of("append_to_response", "credits,keywords"), 12);
        } catch (IOException e) {
            payload = null;
        }

        if (payload == null || payload.containsKey(STATUS_KEY)) {
            Map<String, Object> recovered = title != null ? searchFallback(title, target.year()) : null;
            Map<String, Object> stored = recovered;
            if (stored == null) {
                stored = new LinkedHashMap<>();
                stored.put(MISSING_KEY, true);
                stored.put("fetched_at", now());
            }
            synchronized (lock) {
                memory.put(key, stored);
                dirty = true;
            }
            return recovered;
        }

        Map<String, Object> record = distil(payload, "movie");
        synchronized (lock) {
            memory.put(key, record);
            dirty = true;
        }
        return record;
    }

    /** Fetches a batch in parallel; already-cached entries cost nothing. */
    public void fetchMany(List<Target> items, int workers, boolean retryMissing) {
        List<Target> pending = new ArrayList<>();
        synchronized (lock) {
            for (Target item : items) {
                if (item.tmdbId() == null) {
                    if (item.title() != null && !item.title().isEmpty()) {
                        pending.add(item);
                    }
                    continue;
                }
                Map<String, Object> hit = memory.get(item.tmdbId().toString());
                if (hit == null || (retryMissing && isMissing(hit) && item.title() != null && !item.title().isEmpty())) {
                    pending.add(item);
                }
            }
        }
        if (pending.isEmpty() || routeUnavailable()) {
            return;
        }

        ExecutorService pool = Executors.newFixedThreadPool(workers);
        try {
            List<Future<Map<String, Object>>> futures = new ArrayList<>();
            for (Target item : pending) {
                futures.add(pool.submit(() -> fetch(item, retryMissing)));
            }
            for (Future<Map<String, Object>> future : futures) {
                try {
                    future.get(5, TimeUnit.MINUTES);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception ignored) {
                    // one failed film should not sink the batch
                }
            }
        } finally {
            pool.shutdownNow();
        }
        flushCache();
    }

    /** Cache-only lookup with no network access: used on hot paths. */
    public Map<String, Object> cachedOnly(Long tmdbId) {
        if (tmdbId == null) {
            return null;
        }
        Map<String, Object> hit;
        synchronized (lock) {
            hit = memory.get(tmdbId.toString());
        }
        return hit == null || isMissing(hit) ? null : hit;
    }

    public boolean isKnownMissing(Long tmdbId) {
        if (tmdbId == null) {
            return false;
        }
        synchronized (lock) {
            Map<String, Object> hit = memory.get(tmdbId.toString());
            return hit != null && isMissing(hit);
        }
    }

    private static boolean isMissing(Map<String, Object> record) {
        return Boolean.TRUE.equals(record.get(MISSING_KEY));
    }

    public int cacheSize() {
        synchronized (lock) {
            return (int) memory.values().stream().filter(v -> !isMissing(v)).count();
        }
    }

    public int missingCount() {
        synchronized (lock) {
            return (int) memory.values().stream().filter(TmdbClient::isMissing).count();
        }
    }
}
