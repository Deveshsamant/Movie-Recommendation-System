package com.movierec.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Configuration loading and shared paths. */
public final class AppConfig {

    public final Path root;
    public final Path dataDir;
    public final Path cacheDir;
    private final Map<String, Object> values;

    private AppConfig(Path root, Map<String, Object> values) {
        this.root = root;
        this.dataDir = root.resolve("data");
        this.cacheDir = root.resolve("cache");
        this.values = values;
    }

    /**
     * Defaults, then config.example.json, then config.json, then the environment.
     *
     * <p>config.json is gitignored because it holds a personal TMDB key, so a fresh clone falls
     * back to the example file for everything except the key, which can come from the
     * TMDB_API_KEY environment variable instead.
     */
    public static AppConfig load() {
        Path root = resolveRoot();
        Map<String, Object> cfg = defaults();
        ObjectMapper mapper = new ObjectMapper();
        for (String name : new String[] {"config.example.json", "config.json"}) {
            Path path = root.resolve(name);
            if (!Files.exists(path)) {
                continue;
            }
            try {
                Map<String, Object> loaded = mapper.readValue(path.toFile(), new TypeReference<>() {});
                loaded.remove("_comment");
                cfg = merge(cfg, loaded);
            } catch (IOException ignored) {
                // an unreadable config file is skipped, the next layer still applies
            }
        }

        String envKey = System.getenv("TMDB_API_KEY");
        if (envKey != null && !envKey.isBlank()) {
            cfg.put("tmdb_api_key", envKey.strip());
        }
        String key = String.valueOf(cfg.getOrDefault("tmdb_api_key", ""));
        if (key.isEmpty() || key.equals("null")) {
            // no key anywhere: run without posters rather than failing to start
            cfg.put("posters_enabled", false);
        }

        AppConfig config = new AppConfig(root, cfg);
        try {
            Files.createDirectories(config.cacheDir);
            Files.createDirectories(config.dataDir);
        } catch (IOException e) {
            throw new IllegalStateException("cannot create data/cache folders under " + root, e);
        }
        return config;
    }

    /** The project folder: -Dapp.home, then $APP_HOME, then the current directory. */
    private static Path resolveRoot() {
        String home = System.getProperty("app.home");
        if (home == null || home.isBlank()) {
            home = System.getenv("APP_HOME");
        }
        if (home == null || home.isBlank()) {
            home = ".";
        }
        return Path.of(home).toAbsolutePath().normalize();
    }

    private static Map<String, Object> defaults() {
        Map<String, Object> svd = new LinkedHashMap<>();
        svd.put("n_factors", 64);
        svd.put("n_epochs", 40);
        svd.put("lr", 0.007);
        svd.put("reg", 0.05);
        svd.put("seed", 42);
        Map<String, Object> knn = new LinkedHashMap<>();
        knn.put("k_neighbors", 40);
        knn.put("shrinkage", 25);
        Map<String, Object> hybrid = new LinkedHashMap<>();
        hybrid.put("alpha", 0.55);
        hybrid.put("cold_start_threshold", 8);

        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("tmdb_api_key", "");
        cfg.put("posters_enabled", true);
        cfg.put("tmdb_direct_route", true);
        cfg.put("dataset", "ml-latest-small");
        cfg.put("host", "127.0.0.1");
        cfg.put("port", 8000);
        cfg.put("svd", svd);
        cfg.put("knn", knn);
        cfg.put("hybrid", hybrid);
        return cfg;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> merge(Map<String, Object> base, Map<String, Object> override) {
        Map<String, Object> out = new LinkedHashMap<>(base);
        override.forEach((key, value) -> {
            if (value instanceof Map && out.get(key) instanceof Map) {
                out.put(key, merge((Map<String, Object>) out.get(key), (Map<String, Object>) value));
            } else {
                out.put(key, value);
            }
        });
        return out;
    }

    public String string(String key) {
        Object value = values.get(key);
        return value == null ? "" : value.toString();
    }

    public int integer(String key) {
        return ((Number) values.get(key)).intValue();
    }

    public boolean flag(String key) {
        return Boolean.TRUE.equals(values.get(key));
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> section(String key) {
        return (Map<String, Object>) values.get(key);
    }

    public int integer(String section, String key) {
        return ((Number) section(section).get(key)).intValue();
    }

    public double number(String section, String key) {
        return ((Number) section(section).get(key)).doubleValue();
    }
}
