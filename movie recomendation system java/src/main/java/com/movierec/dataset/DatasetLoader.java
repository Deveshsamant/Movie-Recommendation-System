package com.movierec.dataset;

import com.movierec.config.AppConfig;
import com.movierec.math.CsrMatrix;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * MovieLens loading, indexing and the rating matrix every model shares.
 *
 * <p>Downloads the dataset on first run, then builds a tidy movie catalogue, integer index maps
 * between MovieLens ids and matrix positions, a sparse user x item rating matrix, and a
 * reproducible per-user holdout split used by the evaluation page.
 */
public final class DatasetLoader {

    private static final Map<String, String> DATASET_URLS = Map.of(
            "ml-latest-small", "https://files.grouplens.org/datasets/movielens/ml-latest-small.zip",
            "ml-25m", "https://files.grouplens.org/datasets/movielens/ml-25m.zip");

    private static final Pattern YEAR = Pattern.compile("\\((\\d{4})\\)\\s*$");

    private DatasetLoader() {}

    public static MovieData load(AppConfig config) {
        String name = config.string("dataset");
        try {
            return read(name, ensureDataset(name, config.dataDir));
        } catch (IOException e) {
            throw new IllegalStateException("could not load dataset " + name, e);
        }
    }

    /** Downloads and extracts the MovieLens archive if it is not on disk yet. */
    private static Path ensureDataset(String name, Path dataDir) throws IOException {
        Path folder = dataDir.resolve(name);
        if (Files.exists(folder.resolve("ratings.csv"))) {
            return folder;
        }
        String url = DATASET_URLS.get(name);
        if (url == null) {
            throw new IllegalArgumentException("Unknown dataset: " + name);
        }
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(300))
                .header("User-Agent", "Mozilla/5.0")
                .build();
        byte[] blob;
        try {
            blob = client.send(request, HttpResponse.BodyHandlers.ofByteArray()).body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("download interrupted", e);
        }
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(blob))) {
            Path base = dataDir.toAbsolutePath().normalize();
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                Path target = base.resolve(entry.getName()).normalize();
                if (!target.startsWith(base)) {
                    throw new IOException("unsafe path in archive: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(zip, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        return folder;
    }

    /** Parses RFC 4180 CSV: quoted fields may contain commas and doubled quotes. */
    static List<String[]> readCsv(Path path) throws IOException {
        String text = Files.readString(path, StandardCharsets.UTF_8);
        List<String[]> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < text.length() && text.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    field.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                row.add(field.toString());
                field.setLength(0);
            } else if (c == '\n') {
                row.add(field.toString());
                field.setLength(0);
                rows.add(row.toArray(new String[0]));
                row.clear();
            } else if (c != '\r') {
                field.append(c);
            }
        }
        if (field.length() > 0 || !row.isEmpty()) {
            row.add(field.toString());
            rows.add(row.toArray(new String[0]));
        }
        return rows.isEmpty() ? rows : rows.subList(1, rows.size()); // drop the header
    }

    private static MovieData read(String name, Path folder) throws IOException {
        List<String[]> ratingRows = readCsv(folder.resolve("ratings.csv"));
        int n = ratingRows.size();
        int[] userCol = new int[n];
        int[] movieCol = new int[n];
        double[] ratingCol = new double[n];
        long[] timeCol = new long[n];
        for (int i = 0; i < n; i++) {
            String[] row = ratingRows.get(i);
            userCol[i] = Integer.parseInt(row[0]);
            movieCol[i] = Integer.parseInt(row[1]);
            ratingCol[i] = Double.parseDouble(row[2]);
            timeCol[i] = Long.parseLong(row[3]);
        }

        Map<Integer, Long> tmdbIds = new HashMap<>();
        for (String[] row : readCsv(folder.resolve("links.csv"))) {
            if (row.length > 2 && !row[2].isEmpty()) {
                tmdbIds.put(Integer.parseInt(row[0]), Long.parseLong(row[2]));
            }
        }

        Map<Integer, List<String>> tagMap = new HashMap<>();
        Path tagFile = folder.resolve("tags.csv");
        if (Files.exists(tagFile)) {
            for (String[] row : readCsv(tagFile)) {
                List<String> tags = tagMap.computeIfAbsent(Integer.parseInt(row[1]), key -> new ArrayList<>());
                if (tags.size() < 30) {
                    tags.add(row[2].toLowerCase(Locale.ROOT).strip());
                }
            }
        }

        // per-movie rating count and mean
        Map<Integer, double[]> aggregate = new HashMap<>();
        double ratingSum = 0.0;
        for (int i = 0; i < n; i++) {
            double[] agg = aggregate.computeIfAbsent(movieCol[i], key -> new double[2]);
            agg[0]++;
            agg[1] += ratingCol[i];
            ratingSum += ratingCol[i];
        }

        // keep only movies that actually carry a rating: nothing can be learned about the rest
        TreeMap<Integer, Movie> catalogue = new TreeMap<>();
        for (String[] row : readCsv(folder.resolve("movies.csv"))) {
            int movieId = Integer.parseInt(row[0]);
            double[] agg = aggregate.get(movieId);
            if (agg == null) {
                continue;
            }
            String title = row[1];
            Matcher matcher = YEAR.matcher(title);
            Integer year = null;
            String clean = title.strip();
            if (matcher.find()) {
                year = Integer.valueOf(matcher.group(1));
                clean = title.substring(0, matcher.start()).strip();
            }
            List<String> genres = new ArrayList<>();
            for (String genre : row[2].split("\\|")) {
                if (!genre.isEmpty() && !genre.equals("(no genres listed)")) {
                    genres.add(genre);
                }
            }
            catalogue.put(movieId, new Movie(movieId, title, clean, year, List.copyOf(genres),
                    List.copyOf(tagMap.getOrDefault(movieId, List.of())), tmdbIds.get(movieId),
                    (int) agg[0], agg[1] / agg[0]));
        }

        Movie[] movies = catalogue.values().toArray(new Movie[0]);
        int[] itemIds = new int[movies.length];
        Map<Integer, Integer> itemPos = new HashMap<>();
        for (int i = 0; i < movies.length; i++) {
            itemIds[i] = movies[i].movieId();
            itemPos.put(itemIds[i], i);
        }

        TreeSet<Integer> sortedUsers = new TreeSet<>();
        for (int user : userCol) {
            sortedUsers.add(user);
        }
        int[] userIds = sortedUsers.stream().mapToInt(Integer::intValue).toArray();
        Map<Integer, Integer> userPos = new HashMap<>();
        for (int i = 0; i < userIds.length; i++) {
            userPos.put(userIds[i], i);
        }

        int[] rows = new int[n];
        int[] cols = new int[n];
        for (int i = 0; i < n; i++) {
            rows[i] = userPos.get(userCol[i]);
            cols[i] = itemPos.get(movieCol[i]);
        }
        CsrMatrix full = CsrMatrix.fromTriplets(userIds.length, itemIds.length, rows, cols, ratingCol);

        // Per-user temporal holdout: the newest ~20% of each user's ratings become the test set.
        // A temporal split is the honest one for recommenders: a random split lets the model peek
        // at a user's future taste when predicting their past.
        Map<Integer, List<Integer>> rowsByUser = new TreeMap<>();
        for (int i = 0; i < n; i++) {
            rowsByUser.computeIfAbsent(userCol[i], key -> new ArrayList<>()).add(i);
        }
        Map<Integer, Map<Integer, Double>> testByUser = new LinkedHashMap<>();
        Set<Long> testKeys = new HashSet<>();
        for (Map.Entry<Integer, List<Integer>> entry : rowsByUser.entrySet()) {
            List<Integer> group = entry.getValue();
            group.sort(Comparator.comparingLong(i -> timeCol[i])); // stable, like mergesort
            int size = group.size();
            int nTest = (int) Math.rint(size * 0.2); // rint rounds half-even, like Python's round
            nTest = size > 6 ? Math.max(1, Math.min(nTest, size - 5)) : 0;
            if (nTest <= 0) {
                continue;
            }
            Map<Integer, Double> held = new LinkedHashMap<>();
            for (int i : group.subList(size - nTest, size)) {
                held.put(movieCol[i], ratingCol[i]);
                testKeys.add(pair(userCol[i], movieCol[i]));
            }
            testByUser.put(entry.getKey(), held);
        }

        int kept = 0;
        int[] trainRows = new int[n];
        int[] trainCols = new int[n];
        double[] trainVals = new double[n];
        for (int i = 0; i < n; i++) {
            if (!testKeys.contains(pair(userCol[i], movieCol[i]))) {
                trainRows[kept] = rows[i];
                trainCols[kept] = cols[i];
                trainVals[kept] = ratingCol[i];
                kept++;
            }
        }
        CsrMatrix train = CsrMatrix.fromTriplets(userIds.length, itemIds.length,
                Arrays.copyOf(trainRows, kept), Arrays.copyOf(trainCols, kept), Arrays.copyOf(trainVals, kept));

        return new MovieData(name, movies, userIds, itemIds, userPos, itemPos, full, train, testByUser,
                n, n == 0 ? 0.0 : ratingSum / n);
    }

    private static long pair(int user, int movie) {
        return ((long) user << 32) | (movie & 0xFFFFFFFFL);
    }
}
