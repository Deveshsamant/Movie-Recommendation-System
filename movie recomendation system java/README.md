# Movie Recommender System (Java)

The Java version of the movie recommender in the parent folder: content-based, collaborative and
hybrid filtering on MovieLens, with the same results. It runs on **Java 17, Spring Boot 3.5 and
Maven**, and adds a guided overview that explains the whole project.

## Quick start

**From the zip file:** unzip it and double-click `START-Windows.bat` or `START-Mac.command`, or run
`sh START-Linux.sh`. If the computer has no Java 17 or newer, the start file downloads a private copy.
`HOW-TO-SETUP-AND-RUN.txt` has the details.

**From the source code:** you need a JDK, version 17 or newer. You do not need to install Maven; the
included Maven Wrapper downloads it. On Windows, double-click `setup.bat` once, then
`START-Windows.bat`. On any platform:

```bash
./mvnw package -DskipTests
java -jar target/movie-recommender.jar
```

The app opens your browser at <http://localhost:8000> once it is ready. If port 8000 is taken it uses
the next free one, and you can choose one with `--server.port=8080`.

The first run downloads MovieLens and trains every model, then saves them to `cache/`. Later runs
load from there. Measured on one Windows laptop: about 100 seconds to train everything and compute
the scoreboard, about 20 seconds for a later start (the Python version took 23 seconds under the same
conditions), and about 365 MB of memory while running.

| Extra command | What it does |
|---|---|
| `java -jar target/movie-recommender.jar --no-browser` | Starts without opening a browser |
| `java -jar target/movie-recommender.jar --enrich` | Fetches TMDB metadata for every film, then exits |
| `java -jar target/movie-recommender.jar --enrich --repair` | Same, and retries dead TMDB ids by title search |
| `java -jar target/movie-recommender.jar --prepare` | Trains every model and computes the scoreboard, then exits |
| `./mvnw test` | Runs the unit and API tests |

## Presenting this project

The site is built to be explained to someone seeing it for the first time.

- **Start overview:** the red button opens a 15-step presentation covering the problem, the data, how
  the system is built, each algorithm, the results and every page of the site. Its charts and numbers
  are calculated live by the server.
- **Speaker notes:** press `N` for a suggested script for each step. Press `A` for auto-play. The arrow
  keys move between steps, and `Esc` closes.
- **See it live:** most steps jump to the real page, and a "Back to overview" button returns you to
  the same step.
- **Project guide:** a page with a project summary, a searchable glossary and answers to 16 common
  questions.
- **In simple words:** every page opens with a plain-English explanation.

The overview's charts can be read by hover or keyboard, and most also have a "View as table" link.

### How this differs from the Python site

| Change | Why |
|---|---|
| Added the overview, Project guide and plain-English boxes | To make the project easy to explain |
| Added `GET /api/overview` | Supplies the dataset statistics for the overview's charts. Java only. |
| Removed the "Play" and "Now playing" slideshow | It looked like a video player but played no video, and the overview replaces it |
| Removed "Top 10 this week" | The dataset has no weeks, so the title was wrong |
| Stopped the 3D shelf turning by itself | It distracted from whatever was being explained. The arrows still turn it. |
| Replaced the parallel-lines and flow charts | Simple bar charts are easier to read, and the Hybrid page's split bars already show the same data |
| Replaced typed-in results with live values | Some typed numbers no longer matched the scoreboard, such as 0.0228 where the live value is 0.0240 |
| Collaborative is now blue in charts | Red and blue passed a colour-blindness check in both themes, where red and black did not |

## What was converted

The whole backend is now Java. The only part still in another language is the browser code, because
browsers run JavaScript, not Java.

| Python project | Java project |
|---|---|
| `run.py` | `MovieRecommenderApplication.java` |
| `backend/app.py` (FastAPI) | `web/ApiController.java`, `ApiExceptionHandler.java`, `WebConfig.java` |
| `backend/engine.py` | `engine/Engine.java` |
| `backend/config.py` | `config/AppConfig.java` |
| `backend/dataset.py` | `dataset/DatasetLoader.java`, `MovieData.java`, `Movie.java` |
| `backend/content_based.py` | `model/ContentBasedRecommender.java`, `TfidfModel.java` |
| `backend/collaborative.py` | `model/ItemKnn.java`, `UserKnn.java`, `BiasSvd.java`, `Baseline.java`, `Cf.java` |
| `backend/hybrid.py` | `model/HybridRecommender.java` |
| `backend/evaluate.py` | `model/Evaluator.java` |
| `backend/tmdb.py` | `tmdb/TmdbClient.java` |
| `backend/enrich.py` | `tmdb/Enricher.java` |
| `requirements.txt` | `pom.xml` |
| `setup.bat`, `run.bat`, `Dockerfile` | `setup.bat`, `START-Windows.bat`, `START-Mac.command`, `START-Linux.sh`, `Dockerfile` |

| Python library | Java replacement |
|---|---|
| FastAPI, uvicorn | Spring Boot, embedded Tomcat |
| pandas | `DatasetLoader` (hand-written CSV parsing and grouping) |
| scipy.sparse | `math/CsrMatrix.java` |
| scikit-learn `TfidfVectorizer` | `model/TfidfModel.java` |
| numpy arrays and sorting | plain `double[]`, `math/Num.java` |
| `numpy.random.default_rng` | `math/Pcg64Random.java` |
| `numpy.linalg` | Apache Commons Math |
| requests, urllib3 | `java.net.http`, `SSLSocket` |

### What was not converted

| Part | Why |
|---|---|
| `frontend/` (HTML, CSS, JavaScript) | Browsers cannot run Java. Spring Boot serves it from `src/main/resources/static`, and it now includes the changes listed above. |
| `docs/` report scripts and PDFs | They produce your documentation and are not part of the running app. |
| `design/` | A design prototype, not part of the running app. |
| Python's trained model files (`.npz`) | Java trains its own and saves them as `.bin`. They hold the same numbers. |

## How close it is to the Python version

The Python and Java servers were run side by side on identical copies of the data, and their JSON
responses were compared value by value. This covers the shared API; the overview's new endpoint and
the page changes came afterwards and do not alter those endpoints.

**Every endpoint: 363 API calls, about 261,000 values**

| Calls | Result |
|---|---|
| 313 | Identical |
| 1 | Differs by 0.0001 in the last rounded digit |
| 47 | Differ only because of exact ties (explained below) |
| 2 | Python crashes with a 500 error; Java returns a proper response |

**Every viewer: top-12 lists for all 610 MovieLens viewers across 5 models, 3,050 lists**

| Model | Same list | Same scores |
|---|---|---|
| Content-based | 604 / 610 | 610 / 610 |
| Item-kNN | 609 / 610 | 610 / 610 |
| User-kNN | 609 / 610 | 610 / 610 |
| SVD | 610 / 610 | 610 / 610 |
| Hybrid | 610 / 610 | 606 / 610 (largest gap 0.002) |

- **Lists that differ:** all 8 contain the same scores, and only tied films swap places.
- **SVD training:** the training error matches Python on all 30 epochs, because `Pcg64Random`
  reproduces NumPy's random numbers for the same seed.
- **Scoreboard:** 67 of its 70 numbers match exactly. The other 3 are the last digit of coverage or
  novelty for the two kNN models.

### The one source of difference: ties

When two films have exactly the same score, NumPy's `argsort` and `argpartition` pick an arbitrary
order, which can change with the CPU's instruction set. Java keeps tied films in catalogue order, so
its order is repeatable, but it cannot match NumPy's arbitrary choice.

This shows up in a few places:

- **Item-kNN neighbours:** 3,028 of 9,724 films have a different neighbour set, always among exactly
  tied similarities. These are mostly rarely rated films. Similarities present in both versions agree
  to 1.7e-16. This is also what causes the small hybrid score gaps, because a tied neighbour can shift
  the range used to normalise scores.
- **Viewers with no signal:** a viewer who gives every film the same rating has all collaborative
  scores at exactly 0, so the order of that list means nothing in either version.
- **Rate-a-film sampler:** films tied on rating count can change which ones get picked.

### Deliberate differences

- `GET /api/explain/content` for an unknown viewer, and `GET /api/compare/users` with a non-numeric
  id, crash with a 500 in Python. Java returns an empty explanation and a 422 instead.
- `enrich.py` calls `on_progress(done, len(tmdb_ids))`, but `tmdb_ids` does not exist, and the error
  is silently swallowed. The Python app therefore never refreshes its content model during
  enrichment. Java does what that code intended.
- If the scoreboard is requested while it is still computing in the background, Java waits for that
  result instead of starting a second full evaluation.
- The browser opens as soon as the server is ready, not after a fixed 2.5 seconds.

### Known limits, the same as Python

- The `ml-25m` dataset is listed but impractical, because user-kNN keeps a dense viewer-by-viewer
  matrix in memory.

## Project layout

```
src/main/java/com/movierec/
  MovieRecommenderApplication.java  entry point, --enrich and --prepare modes
  config/AppConfig.java             defaults < config.example.json < config.json < environment
  dataset/                          MovieLens download, catalogue, rating matrix, temporal split
  math/                             sparse matrix, Python-style rounding, NumPy's random generator
  model/                            TF-IDF, kNN, SVD, baseline, hybrid, evaluation
  tmdb/                             TMDB client with route failover and cache, background enrichment
  engine/Engine.java                builds everything, custom viewers, search, PCA, status
  web/                              REST endpoints, error format, CORS, cache headers
src/main/resources/static/          the front-end
src/test/java/                      JUnit tests, checked against NumPy and scikit-learn values
```

## Configuration

`config.example.json` is the template, and the settings mean the same as in the Python version. Put
your TMDB key in `config.json` or the `TMDB_API_KEY` environment variable. `config.json`, `data/`,
`cache/` and `target/` are gitignored, so your key is not committed. Without a key the app still
runs, with placeholder artwork.

The app reads `config.json`, `data/` and `cache/` from the folder you start it in. To use another
folder, set `-Dapp.home=...` or the `APP_HOME` environment variable.

## Deploying

`docker build` uses the included `Dockerfile`. The container reads `$PORT`, binds `0.0.0.0` and
exposes 7860, like the Python image, so the hosting steps in the parent folder's `DEPLOY.md` still
apply. The Dockerfile has not been built yet, because Docker was not available on the machine this
was developed on.

## Licence and credits

MIT, as in the parent project. Data from
[MovieLens](https://grouplens.org/datasets/movielens/) (GroupLens) and
[TMDB](https://www.themoviedb.org/). This product uses the TMDB API but is not endorsed or certified
by TMDB.
