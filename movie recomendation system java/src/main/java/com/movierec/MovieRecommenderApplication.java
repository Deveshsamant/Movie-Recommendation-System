package com.movierec;

import com.movierec.config.AppConfig;
import com.movierec.dataset.DatasetLoader;
import com.movierec.dataset.MovieData;
import com.movierec.engine.Engine;
import com.movierec.tmdb.Enricher;
import com.movierec.tmdb.TmdbClient;
import java.awt.Desktop;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * Starts the movie recommender.
 *
 * <pre>java -jar target/movie-recommender.jar</pre>
 *
 * <p>The first run downloads MovieLens (~1 MB) and trains every model; results are cached, so later
 * starts are quick. Two extra modes run a job and exit instead of serving:
 *
 * <ul>
 *   <li>--enrich [--repair]: fetch TMDB metadata; --repair retries dead ids via title search
 *   <li>--prepare: train every model and compute the scoreboard
 * </ul>
 */
@SpringBootApplication
public class MovieRecommenderApplication {

    public static void main(String[] args) throws InterruptedException {
        List<String> flags = Arrays.asList(args);
        AppConfig config = AppConfig.load();
        if (flags.contains("--enrich")) {
            enrich(config, flags.contains("--repair"));
            return;
        }
        if (flags.contains("--prepare")) {
            TmdbClient tmdb = new TmdbClient(config);
            new Engine(config, tmdb, new Enricher(tmdb), true).evaluation(10, false);
            return;
        }

        // Hosting platforms inject the port and expect 0.0.0.0; binding 127.0.0.1 inside a container
        // makes the app unreachable from outside it.
        String envPort = System.getenv("PORT");
        boolean deployed = envPort != null && !envPort.isEmpty();
        String envHost = System.getenv("HOST");
        String host = envHost != null && !envHost.isEmpty() ? envHost
                : deployed ? "0.0.0.0" : config.string("host");
        int port = deployed ? Integer.parseInt(envPort) : config.integer("port");
        Integer requested = requestedPort(args);
        if (requested != null) {
            port = requested;
        } else if (!deployed) {
            // On someone else's computer the usual port may already be taken.
            int free = firstFreePort(host, port);
            if (free != port) {
                System.out.println("  port " + port + " is already in use, using " + free);
            }
            port = free;
        }
        String url ="http://" + (host.equals("0.0.0.0") || host.equals("127.0.0.1") ? "localhost" : host) + ":" + port;

        System.out.println();
        System.out.println("  Movie Recommender System - content, collaborative and hybrid");
        System.out.println("  " + "-".repeat(52));
        System.out.println(deployed ? "  serving on " + host + ":" + port : "  starting " + url);
        System.out.println("  first run trains the models; later runs load from cache");
        System.out.println();

        boolean browser = !deployed && !flags.contains("--no-browser");
        SpringApplication app = new SpringApplication(MovieRecommenderApplication.class);
        app.setDefaultProperties(Map.of("server.port", port, "server.address", host));
        app.setHeadless(!browser); // Desktop.browse needs a non-headless JVM
        app.run(args); // returns once the models are trained and the server is accepting requests
        System.out.println("  ready at " + url);
        if (browser) {
            openBrowser(url);
        }
    }

    /** The value of a --server.port=N argument, which Spring Boot also honours, or null. */
    private static Integer requestedPort(String[] args) {
        for (String arg : args) {
            if (arg.startsWith("--server.port=")) {
                return Integer.valueOf(arg.substring("--server.port=".length()));
            }
        }
        return null;
    }

    /** The first port from start onwards that can be opened, or start if none in range can. */
    private static int firstFreePort(String host, int start) {
        for (int port = start; port < start + 20; port++) {
            try (ServerSocket probe = new ServerSocket(port, 1, InetAddress.getByName(host))) {
                return port;
            } catch (IOException taken) {
                // try the next one
            }
        }
        return start;
    }

    private static void openBrowser(String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
                return;
            }
        } catch (Exception | LinkageError e) {
            // a minimal Linux system may have no desktop libraries: try the next way
        }
        try {
            new ProcessBuilder("xdg-open", url).start();
        } catch (IOException e) {
            System.out.println("  could not open a browser; visit " + url);
        }
    }

    private static void enrich(AppConfig config, boolean repair) throws InterruptedException {
        MovieData data = DatasetLoader.load(config);
        if (!config.flag("posters_enabled")) {
            System.out.println("no TMDB key configured - MovieLens is ready, skipping film metadata");
            return;
        }
        TmdbClient tmdb = new TmdbClient(config);
        Enricher enricher = new Enricher(tmdb);
        System.out.println("route: " + tmdb.routeStatus());
        enricher.start(data, 24, (done, total) -> System.out.println("  " + done + "/" + total), repair);
        while (enricher.isRunning()) {
            Thread.sleep(1000);
        }
        System.out.println("done: " + enricher.status() + " | cached: " + tmdb.cacheSize() + " | missing: " + tmdb.missingCount());
    }

    @Bean
    AppConfig appConfig() {
        return AppConfig.load();
    }

    @Bean
    TmdbClient tmdbClient(AppConfig config) {
        return new TmdbClient(config);
    }

    @Bean
    Enricher enricher(TmdbClient tmdb) {
        return new Enricher(tmdb);
    }

    /** Built before the server accepts connections, so a request never sees half-trained models. */
    @Bean
    Engine engine(AppConfig config, TmdbClient tmdb, Enricher enricher) {
        Engine engine = new Engine(config, tmdb, enricher, true);
        if (config.flag("posters_enabled")) {
            engine.startEnrichment();
        }
        engine.warmEvaluation();
        return engine;
    }
}
