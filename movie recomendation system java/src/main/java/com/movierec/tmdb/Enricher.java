package com.movierec.tmdb;

import com.movierec.dataset.Movie;
import com.movierec.dataset.MovieData;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Progressive TMDB enrichment.
 *
 * <p>The app is fully usable the moment it starts (MovieLens genres + tags are enough for a working
 * content model). This warmer runs in the background and folds in TMDB keywords / cast / directors
 * in popularity order, so the movies people actually see get richer first. Progress is persisted,
 * so it resumes.
 */
public final class Enricher {

    private static final int BATCH = 40;

    private final TmdbClient tmdb;
    private boolean running;
    private int done;
    private int total;
    private Double startedAt;
    private Double finishedAt;
    private double rate;

    public Enricher(TmdbClient tmdb) {
        this.tmdb = tmdb;
    }

    private static double now() {
        return System.currentTimeMillis() / 1000.0;
    }

    public Map<String, Object> status() {
        Map<String, Object> state = new LinkedHashMap<>();
        synchronized (this) {
            state.put("running", running);
            state.put("done", done);
            state.put("total", total);
            state.put("started_at", startedAt);
            state.put("finished_at", finishedAt);
            state.put("rate", rate);
            int remaining = Math.max(0, total - done);
            state.put("cached", tmdb.cacheSize());
            state.put("eta_seconds", rate > 0.05 ? (Object) (int) (remaining / rate) : null);
        }
        return state;
    }

    public synchronized boolean isRunning() {
        return running;
    }

    /**
     * Kicks off enrichment for every uncached film, most-rated first.
     *
     * @param retryMissing also revisits films previously marked missing, this time searching TMDB
     *     by title: MovieLens ids for miniseries and re-issued entries are frequently dead.
     */
    public boolean start(MovieData data, int workers, BiConsumer<Integer, Integer> onProgress, boolean retryMissing) {
        synchronized (this) {
            if (running) {
                return false;
            }
        }

        Movie[] ordered = data.movies.clone();
        Arrays.sort(ordered, Comparator.comparingInt(Movie::nRatings).reversed());
        List<TmdbClient.Target> pending = new ArrayList<>();
        for (Movie movie : ordered) {
            Long id = movie.tmdbId();
            if (tmdb.cachedOnly(id) != null) {
                continue;
            }
            if (!retryMissing && id != null && tmdb.isKnownMissing(id)) {
                continue;
            }
            pending.add(new TmdbClient.Target(id, movie.cleanTitle(), movie.year()));
        }

        if (pending.isEmpty()) {
            synchronized (this) {
                done = 0;
                total = 0;
                finishedAt = now();
            }
            return false;
        }

        synchronized (this) {
            running = true;
            done = 0;
            total = pending.size();
            startedAt = now();
            finishedAt = null;
            rate = 0.0;
        }
        Thread thread = new Thread(() -> work(pending, workers, onProgress, retryMissing), "tmdb-enrich");
        thread.setDaemon(true);
        thread.start();
        return true;
    }

    private void work(List<TmdbClient.Target> targets, int workers, BiConsumer<Integer, Integer> onProgress, boolean retryMissing) {
        long started = System.nanoTime();
        for (int start = 0; start < targets.size(); start += BATCH) {
            List<TmdbClient.Target> chunk = targets.subList(start, Math.min(start + BATCH, targets.size()));
            try {
                tmdb.fetchMany(chunk, workers, retryMissing);
            } catch (RuntimeException ignored) {
                // a bad batch only delays those films until the next start
            }
            int finished = Math.min(start + BATCH, targets.size());
            double elapsed = Math.max(1e-6, (System.nanoTime() - started) / 1e9);
            synchronized (this) {
                done = finished;
                rate = finished / elapsed;
            }
            if (onProgress != null) {
                try {
                    onProgress.accept(finished, targets.size());
                } catch (RuntimeException ignored) {
                    // a failed checkpoint must not stop the download
                }
            }
        }
        tmdb.flushCache();
        synchronized (this) {
            running = false;
            finishedAt = now();
        }
    }
}
