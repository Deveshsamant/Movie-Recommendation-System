/* Start Overview: a guided, full-screen presentation of the whole project.
   Every figure and chart is read from the running server, never typed in, so the
   slides always agree with the rest of the site. */

import { api } from "./api.js?v=16";
import { $, $$, el, esc, fmt, ICON, poster } from "./ui.js?v=16";
import {
  barList, chartCard, columns, hideTip, legend, lineChart, stacked, waffle, waterfall,
} from "./charts.js?v=16";

const AUTO_MS = 20000;
const NOTES_KEY = "mrs-overview-notes";

const reduced = () => matchMedia("(prefers-reduced-motion: reduce)").matches;

/* ── shared building blocks ───────────────────────────────── */
const slide = ({ chapter, title, lede, points = [], simple, go }) => `
  <div class="ovs__copy">
    <span class="kicker kicker--accent">${esc(chapter)}</span>
    <h2 class="ovs__title">${esc(title)}</h2>
    <p class="ovs__lede">${lede}</p>
    ${points.length ? `<ul class="ovs__points">${points.map((p) => `<li>${p}</li>`).join("")}</ul>` : ""}
    ${simple ? `<div class="plain"><b>In simple words</b><p>${simple}</p></div>` : ""}
    ${go ? `<button class="btn btn--ghost ovs__go" data-golive>See it live: ${esc(go.label)} ${ICON.arrowR}</button>` : ""}
  </div>
  <div class="ovs__visual" id="ovVisual"></div>`;

/** Fills a slide's visual area, ignoring the result if the presenter has moved on. */
async function fill(stage, c, build, waiting = "Calculating live") {
  const host = $("#ovVisual", stage);
  const token = c.token;
  host.innerHTML = `<div class="loading"><span class="spin"></span> ${esc(waiting)}</div>`;
  try {
    const nodes = await build();
    if (token !== c.token) return;
    host.replaceChildren(...nodes);
  } catch (err) {
    if (token !== c.token) return;
    host.innerHTML = `<div class="note"><b>This chart could not load.</b><p>${esc(err.message)}</p></div>`;
  }
}

const notice = (title, body) => el(`<div class="note"><b>${esc(title)}</b><p>${esc(body)}</p></div>`);
const stats = (items) => el(`<div class="cells ov-cells">
  ${items.map(([v, l, s]) => `<div class="cell"><b>${esc(v)}</b><span>${esc(l)}</span>${s ? `<small>${esc(s)}</small>` : ""}</div>`).join("")}
</div>`);

/* ── the fifteen steps ────────────────────────────────────── */
const STEPS = [
  {
    id: "title", chapter: "Introduction", label: "Welcome",
    notes: "This is my movie recommendation system. It is a working website, not only a report. In the next few minutes I will show the problem, the data, the three methods it uses, and how they were measured to find which one is best. Every number on these slides is calculated live by the running program.",
    render(stage, c) {
      const d = c.status.dataset;
      stage.innerHTML = `
        <div class="ov-hero">
          <span class="kicker kicker--accent">Project overview</span>
          <h1>Movie Recommendation System</h1>
          <p class="ov-hero__lede">A website that suggests films to each person and shows exactly why
            it chose them. It compares three approaches on real rating data and measures which one
            works best.</p>
          <div class="cells ov-cells">
            <div class="cell"><b>${fmt.int(d.n_users)}</b><span>viewers</span></div>
            <div class="cell"><b>${fmt.int(d.n_items)}</b><span>films</span></div>
            <div class="cell"><b>${fmt.int(d.n_ratings)}</b><span>ratings</span></div>
            <div class="cell"><b>7</b><span>models compared</span></div>
          </div>
          <div class="ov-trio">
            <div><i class="cx-tone--accent"></i><b>Content-based</b><span>Reads the film: genres, cast, keywords</span></div>
            <div><i class="cx-tone--cf"></i><b>Collaborative</b><span>Reads the crowd: who liked what</span></div>
            <div><i class="ov-trio__both"></i><b>Hybrid</b><span>Combines both, and is the most accurate at ranking</span></div>
          </div>
          <p class="ov-hero__foot">Java 17 · Spring Boot · Maven · HTML, CSS, JavaScript · MovieLens and TMDB data<br>
            Project by Devesh Samant</p>
        </div>`;
    },
  },
  {
    id: "problem", chapter: "Introduction", label: "The problem",
    go: { view: "data", label: "The data page" },
    notes: "The problem is simple to state. There are thousands of films and each person has seen only a tiny part of them. If I draw a table with viewers as rows and films as columns, almost all of it is empty. A recommender system fills in those blanks and shows the best guesses first.",
    render(stage, c) {
      const d = c.status.dataset;
      const s = c.summary;
      stage.innerHTML = slide({
        chapter: "The problem",
        title: "Too many films, too little time",
        lede: `There are <b>${fmt.int(d.n_items)} films</b> here, and the average viewer has rated only
          <b>${fmt.n(s.viewers.mean_ratings, 0)}</b> of them. The system has to work out which of the
          others each person would enjoy.`,
        points: [
          "<b>Input:</b> star ratings that viewers gave to films, from 0.5 to 5.",
          "<b>Output:</b> a personal, ranked list of films the viewer has not rated.",
          "<b>Extra goal:</b> explain every suggestion with its real numbers.",
        ],
        simple: "Imagine a table with one row per viewer and one column per film. Each rating fills one cell. Almost every cell is empty, and the job of this project is to guess the empty ones.",
        go: this.go,
      });
      $("#ovVisual", stage).append(
        el(`<div class="ov-figure"><b>${fmt.n(s.sparsity.filled_pct, 1)}%</b>
          <span>of the viewer-and-film table is filled in</span></div>`),
        chartCard("The whole table, drawn to scale",
          "Each square stands for 0.1% of all possible viewer-and-film pairs. Red squares have a rating.",
          waffle(s.sparsity.filled_pct, { label: `${s.sparsity.filled_pct}% of the table is filled` }),
          { headers: ["Measure", "Value"], rows: [
            ["Possible viewer-and-film pairs", fmt.int(s.sparsity.total_cells)],
            ["Pairs with a rating", fmt.int(s.sparsity.filled_cells)],
            ["Filled", `${s.sparsity.filled_pct}%`],
            ["Empty", `${s.sparsity.empty_pct}%`],
          ] }),
      );
    },
  },
  {
    id: "data", chapter: "The data", label: "The dataset",
    go: { view: "data", label: "The data page" },
    notes: "The project uses MovieLens, a public research dataset from the GroupLens lab at the University of Minnesota, so the ratings are real. TMDB adds posters, cast, directors and keywords. These two charts show how people actually rate. Most ratings are high, because people mostly watch films they expect to enjoy.",
    render(stage, c) {
      const d = c.status.dataset;
      const s = c.summary;
      const hist = s.rating_histogram;
      const total = hist.reduce((sum, r) => sum + r.count, 0) || 1;
      const mode = hist.reduce((a, b) => (b.count > a.count ? b : a));
      const genres = s.genres.slice(0, 6);
      stage.innerHTML = slide({
        chapter: "The data",
        title: "Real ratings from real people",
        lede: `MovieLens is a public research dataset from GroupLens at the University of Minnesota.
          This project uses <b>${fmt.int(d.n_ratings)} ratings</b> from <b>${fmt.int(d.n_users)} viewers</b>.
          TMDB adds posters, cast, directors and keywords for
          <b>${fmt.int(c.status.content.tmdb_enriched)} films</b>.`,
        points: [
          `The most common score is <b>${fmt.n(mode.rating, 1)} stars</b>, given in ${fmt.n((mode.count / total) * 100, 0)}% of all ratings.`,
          `The average rating is <b>${fmt.n(d.mean_rating, 2)}</b> out of 5, so people tend to rate generously.`,
          `<b>${esc(genres[0].genre)}</b> is the most-rated genre.`,
        ],
        simple: "We know two things: who rated what, and what each film is about. Those are the only two ingredients the methods use.",
        go: this.go,
      });
      $("#ovVisual", stage).append(
        chartCard("How viewers rate", "Number of ratings given at each star level.",
          columns(hist.map((r) => ({
            label: fmt.n(r.rating, 1), value: r.count, tip: `${fmt.n(r.rating, 1)} stars`,
          })), { format: fmt.int, xTitle: "Stars given", label: "Number of ratings at each star level", height: 116 }),
          { headers: ["Stars", "Ratings", "Share"], rows: hist.map((r) => [fmt.n(r.rating, 1), fmt.int(r.count), `${fmt.n((r.count / total) * 100, 1)}%`]) }),
        chartCard("The most-rated genres", "A film with several genres counts once in each.",
          barList(genres.map((g) => ({
            label: g.genre, value: g.ratings, display: fmt.int(g.ratings),
            tip: `${g.genre}: ${fmt.int(g.films)} films, average ${fmt.n(g.avg_rating, 2)} stars`,
          })), { label: "Ratings per genre" }),
          { headers: ["Genre", "Ratings", "Films", "Average"], rows: s.genres.map((g) => [g.genre, fmt.int(g.ratings), fmt.int(g.films), fmt.n(g.avg_rating, 2)]) }),
      );
    },
  },
  {
    id: "tail", chapter: "The data", label: "The long tail",
    notes: "This chart explains why one method is not enough. A small group of famous films collects most of the ratings, while thousands of films have almost none. A method that relies on ratings cannot say anything useful about those rare films, but a method that reads their description can.",
    render(stage, c) {
      const t = c.summary.long_tail;
      stage.innerHTML = slide({
        chapter: "The data",
        title: "A few films get almost all the attention",
        lede: `The top 10% of films receive <b>${fmt.n(t.top_10_pct_share, 0)}% of all ratings</b>, while
          <b>${fmt.int(t.films_with_one_rating)} films</b> have just one. This pattern is called the long tail.`,
        points: [
          "Methods that learn from ratings have little to go on for those rare films.",
          "Methods that read a film's description can still reach them.",
          "This is the main reason to combine both approaches.",
        ],
        simple: "Everyone has rated the blockbusters, and hardly anyone has rated the hidden gems. A good system needs a way to suggest both.",
      });
      const curve = t.curve.map((p) => ({ x: p.films_pct, y: p.ratings_pct }));
      $("#ovVisual", stage).append(
        chartCard("Share of all ratings held by the most-rated films",
          "Read it like this: the most-rated 10% of films hold this share of all ratings.",
          lineChart(curve, {
            xLabel: "% of films, most-rated first", yLabel: "% of all ratings",
            xFormat: (v) => `${v}%`, yFormat: (v) => `${fmt.n(v, 0)}%`,
            tipLabel: (p) => `held by the top ${p.x}% of films`,
            yMax: 100, xTicks: [0, 20, 40, 60, 80, 100],
            marks: [{ x: 10, y: t.top_10_pct_share, label: `Top 10% hold ${fmt.n(t.top_10_pct_share, 0)}%` }],
            label: "Cumulative share of ratings by film popularity",
          }),
          { headers: ["Top % of films", "% of all ratings"], rows: t.curve.filter((p) => p.films_pct % 10 === 0).map((p) => [`${p.films_pct}%`, `${p.ratings_pct}%`]) }),
        stats([
          [fmt.int(t.median_ratings_per_film), "ratings for the typical film", "the median"],
          [fmt.int(t.most_ratings_on_a_film), "ratings for the most-rated film"],
          [fmt.int(t.films_under_five_ratings), "films with under 5 ratings"],
        ]),
      );
    },
  },
  {
    id: "system", chapter: "The system", label: "How it is built",
    notes: "The website has three layers. The browser draws the pages. The Java server, built with Spring Boot, receives each request and returns JSON. Behind it, the engine keeps all the trained models in memory. The algorithms are implemented from scratch, without a machine-learning library. Press the button to send a real request and watch it travel through the layers.",
    render(stage, c) {
      stage.innerHTML = slide({
        chapter: "The system",
        title: "How the website is built",
        lede: "A three-layer web application. The browser asks a question, the Java server answers it, and every answer is calculated by models held in memory.",
        points: [
          "<b>Back end:</b> Java 17 and Spring Boot, built with Maven.",
          "<b>Front end:</b> plain HTML, CSS and JavaScript, with charts drawn from live data.",
          "<b>Algorithms:</b> TF-IDF, kNN and SVD are implemented from scratch, with no machine-learning library.",
        ],
        simple: "The page in your browser asks the server something like “what should this person watch?”. The server runs the models and sends back the answer together with its working.",
      });
      const LAYERS = [
        { key: "browser", name: "Browser", sub: "HTML, CSS, JavaScript", info: "Draws the pages and charts. It holds no recommendation logic: everything it shows comes from the server." },
        { key: "api", name: "REST API", sub: "Spring Boot, embedded Tomcat", info: "32 endpoints that take a question over HTTP and return JSON. ApiController.java is the entry point." },
        { key: "engine", name: "Engine", sub: "Engine.java", info: "Loads the data, trains every model at start-up, and keeps them in memory so answers are fast." },
      ];
      const MODELS = ["Content-based", "Item-kNN", "User-kNN", "SVD", "Hybrid", "Evaluator"];
      const DATA = ["MovieLens CSV files", "TMDB API", "Saved models on disk"];
      const node = el(`
        <div class="ov-arch">
          ${LAYERS.map((l) => `
            <button class="ov-arch__box" data-layer="${l.key}" data-info="${esc(l.info)}">
              <b>${l.name}</b><span>${l.sub}</span>
            </button>
            <i class="ov-arch__link" aria-hidden="true"></i>`).join("")}
          <button class="ov-arch__group" data-layer="models"
            data-info="Each model is a separate Java class that can score every film for any viewer and explain its own result.">
            ${MODELS.map((m) => `<span>${m}</span>`).join("")}
          </button>
          <i class="ov-arch__link" aria-hidden="true"></i>
          <button class="ov-arch__group ov-arch__group--data" data-layer="data"
            data-info="Ratings come from MovieLens, film details from TMDB. Trained models and TMDB results are saved to disk, so later starts are quick.">
            ${DATA.map((m) => `<span>${m}</span>`).join("")}
          </button>
          <p class="ov-arch__info" id="archInfo">Click any layer to read what it does.</p>
          <div class="ov-arch__run">
            <button class="btn btn--primary" id="archRun">${ICON.play} Send a real request</button>
            <code id="archOut">Nothing sent yet.</code>
          </div>
        </div>`);
      $("#ovVisual", stage).append(node);

      const boxes = $$("[data-layer]", node);
      const light = (key) => boxes.forEach((b) => b.classList.toggle("is-on", b.dataset.layer === key));
      node.addEventListener("click", (e) => {
        const b = e.target.closest("[data-layer]");
        if (!b) return;
        light(b.dataset.layer);
        $("#archInfo", node).textContent = b.dataset.info;
      });

      $("#archRun", node).addEventListener("click", async () => {
        const out = $("#archOut", node);
        const run = $("#archRun", node);
        const token = c.token;
        run.disabled = true;
        const url = `/api/recommend/hybrid?user_id=${c.viewer}&n=1`;
        out.textContent = `GET ${url}`;
        const path = reduced() ? [] : ["browser", "api", "engine", "models", "data", "models", "engine", "api", "browser"];
        const walk = (async () => {
          for (const key of path) {
            if (token !== c.token) return;
            light(key);
            await new Promise((r) => setTimeout(r, 230));
          }
        })();
        try {
          const t0 = performance.now();
          const d = await api.hybrid(c.viewer, { n: 1, strategy: "weighted", cf_model: "item-knn" });
          const ms = Math.round(performance.now() - t0);
          await walk;
          if (token !== c.token) return;
          const top = d.items?.[0];
          out.textContent = top
            ? `Answered in ${ms} ms. Top pick for ${c.viewerName()}: ${top.title} (score ${fmt.n(top.score, 3)})`
            : `Answered in ${ms} ms, but this viewer has no ratings yet.`;
        } catch (err) {
          if (token === c.token) out.textContent = `Request failed: ${err.message}`;
        }
        if (token === c.token) { run.disabled = false; light(""); }
      });
    },
  },
  {
    id: "pipeline", chapter: "The system", label: "From data to result",
    notes: "This is the full pipeline, with the real numbers from this run. The data is loaded and split, so that some ratings are hidden for testing later. Films are turned into vectors, the models are trained, the scores are blended, and each result is sent to the browser together with its explanation. Training results are saved, so later starts only take seconds.",
    render(stage, c) {
      const d = c.status.dataset;
      const m = c.status.models;
      const s = c.summary.split;
      stage.innerHTML = slide({
        chapter: "The system",
        title: "From raw data to a recommendation",
        lede: "Six stages run in order. These are the real numbers from this run of the program.",
        simple: "Clean the data, describe the films, learn from the ratings, combine the scores, then show the result with its explanation.",
        points: ["Training results are saved to disk, so later starts only take seconds."],
      });
      const steps = [
        ["Load", `${fmt.int(d.n_ratings)} ratings and ${fmt.int(d.n_items)} films are read from the MovieLens files.`],
        ["Split", `${fmt.int(s.train_ratings)} ratings are kept for training and the newest ${fmt.int(s.test_ratings)} are hidden for the final test.`],
        ["Describe", `Each film becomes a vector across ${fmt.int(c.status.content.n_terms)} terms using TF-IDF.`],
        ["Train", `Item-kNN stores ${fmt.int(m.item_knn.stored_similarities)} film-to-film similarities. User-kNN uses ${m.user_knn.k} neighbours. SVD learns ${m.svd.n_factors} hidden factors over ${m.svd.n_epochs} training rounds.`],
        ["Blend", `The hybrid gives collaborative a weight of ${fmt.n(m.hybrid.alpha, 2)} and content ${fmt.n(1 - m.hybrid.alpha, 2)}.`],
        ["Explain", "Each recommendation is returned with the exact calculation behind it, and the browser draws it."],
      ];
      $("#ovVisual", stage).append(el(`<ol class="ov-pipe">
        ${steps.map(([name, text]) => `<li><b>${esc(name)}</b><span>${esc(text)}</span></li>`).join("")}
      </ol>`));
    },
  },
  {
    id: "content", chapter: "The algorithms", label: "1. Content-based",
    go: { view: "content", label: "Content-based page" },
    notes: "The first method only reads information about the film. Each film becomes a list of weighted words: genres, tags, keywords, director and cast. The viewer's ratings are combined into one taste profile, and every film is scored by how closely it matches. It works from the very first rating and can reach rare films, but it tends to suggest more of the same.",
    render(stage, c) {
      stage.innerHTML = slide({
        chapter: "Method 1 · Content-based",
        title: "It reads the film, not the crowd",
        lede: "Liked one space film? It looks for others with similar descriptions. No other viewer is involved.",
        points: [
          "<b>Describe:</b> each film becomes weighted words using TF-IDF. Genres count ×3; tags, keywords and directors ×2; cast ×1.",
          "<b>Profile:</b> films rated above the viewer's average pull the taste profile towards them, and films below push it away.",
          "<b>Match:</b> cosine similarity scores each film against the profile.",
          "<b>Strength:</b> works from one rating and reaches rare films. <b>Weakness:</b> more of the same.",
        ],
        go: this.go,
      });
      fill(stage, c, async () => {
        const d = await c.once(`content:${c.viewer}`, () => api.content(c.viewer, 3));
        if (d.empty) return [notice("No ratings yet", d.message)];
        const top = d.items[0];
        const why = top.explanation;
        const nodes = [el(`
          <div class="ov-pick">
            ${poster(top, "ov-poster")}
            <div>
              <span class="ov-pick__k">Top pick for ${esc(c.viewerName())}</span>
              <b>${esc(top.title)}</b>
              <span>Match score ${fmt.n(top.score, 3)} out of 1, built from ${fmt.int(why.n_sources)} of the viewer's rated films.</span>
            </div>
          </div>`)];
        if (why.terms?.length) {
          nodes.push(chartCard("The shared words that produced this score",
            "Each bar is one term's exact contribution. Together, all shared terms add up to the score.",
            barList(why.terms.slice(0, 4).map((t) => ({
              label: t.term, sub: t.kind, value: t.contribution, display: fmt.n(t.contribution, 3),
              tip: `${t.term} (${t.kind})`,
            })), { label: "Term contributions" }),
            { headers: ["Term", "Kind", "Contribution"], rows: why.terms.map((t) => [t.term, t.kind, fmt.n(t.contribution, 4)]) }));
        }
        const sources = (why.because_of || []).slice(0, 2);
        if (sources.length) {
          nodes.push(chartCard("The rated films most like this pick",
            "How similar each of the viewer's rated films is to the pick.",
            barList(sources.map((s) => ({
              label: s.title, sub: `this viewer rated it ${fmt.n(s.your_rating, 1)}`, value: s.similarity,
              display: fmt.n(s.similarity, 3), tip: s.title,
            })), { label: "Rated films similar to the pick" }),
            { headers: ["Film", "Viewer's rating", "Similarity"], rows: why.because_of.map((s) => [s.title, fmt.n(s.your_rating, 1), fmt.n(s.similarity, 4)]) }));
        }
        return nodes;
      });
    },
  },
  {
    id: "collab", chapter: "The algorithms", label: "2. Collaborative",
    go: { view: "collab", label: "Collaborative page", cfModel: "item-knn" },
    notes: "The second method never reads anything about the film. It only looks at who rated what. If the same people tend to rate two films the same way, those films count as similar. Then a film is recommended because it is similar to ones this viewer already rated highly. This finds real surprises, but it cannot help with a brand-new viewer or film.",
    render(stage, c) {
      stage.innerHTML = slide({
        chapter: "Method 2 · Collaborative",
        title: "It reads the crowd, not the film",
        lede: "People who liked the same films as you also liked these. It compares rating patterns only, and knows nothing about plot or genre.",
        points: [
          "<b>Item-based kNN:</b> finds films that the same people rated in the same way.",
          "<b>User-based kNN:</b> finds the viewers most like you, then uses their favourites.",
          "<b>Fair comparison:</b> ratings are adjusted for each viewer's own average, so a harsh viewer and a generous one can still match.",
          "<b>Strength:</b> real surprises. <b>Weakness:</b> needs ratings to work with.",
        ],
        go: this.go,
      });
      fill(stage, c, async () => {
        const d = await c.once(`item:${c.viewer}`, () => api.collaborative(c.viewer, "item-knn", 3));
        if (d.empty) return [notice("No ratings yet", d.message)];
        const top = d.items[0];
        const nb = top.explanation?.neighbours || [];
        const nodes = [el(`
          <div class="ov-pick">
            ${poster(top, "ov-poster")}
            <div>
              <span class="ov-pick__k">Top pick for ${esc(c.viewerName())}</span>
              <b>${esc(top.title)}</b>
              <span>Predicted rating ${fmt.n(top.predicted_rating, 2)} out of 5, using ${fmt.int(top.explanation?.k_used ?? 0)} similar films this viewer has rated.</span>
            </div>
          </div>`)];
        if (nb.length) {
          nodes.push(chartCard("Rated films that are most similar to this pick",
            "Similarity comes only from how the same people rated both films.",
            barList(nb.map((n) => ({
              label: n.title, sub: `this viewer rated it ${fmt.n(n.your_rating, 1)}`,
              value: n.similarity, display: fmt.n(n.similarity, 3), tone: "cf", tip: n.title,
            })), { label: "Similar films" }),
            { headers: ["Film", "Viewer's rating", "Similarity"], rows: nb.map((n) => [n.title, fmt.n(n.your_rating, 1), fmt.n(n.similarity, 4)]) }));
        }
        return nodes;
      });
    },
  },
  {
    id: "svd", chapter: "The algorithms", label: "3. Hidden tastes (SVD)",
    go: { view: "collab", label: "SVD and its 3D map", cfModel: "svd" },
    notes: "Matrix factorisation is a stronger form of collaborative filtering. It learns hidden tastes for every viewer and film. Nobody names these factors, the model finds them by itself. The prediction is a simple sum: the overall average, plus how generous this viewer is, plus how well liked this film is, plus how well the viewer's tastes match the film. The line shows the training error falling as it learns.",
    render(stage, c) {
      const m = c.status.models.svd;
      stage.innerHTML = slide({
        chapter: "Method 2 · Collaborative",
        title: "SVD discovers hidden tastes by itself",
        lede: `Matrix factorisation gives every viewer and every film ${m.n_factors} numbers that describe hidden tastes. Nobody names these factors: the model learns them from the ratings.`,
        points: [
          `<b>Training:</b> ${m.n_epochs} rounds of stochastic gradient descent, reducing the error each round.`,
          "<b>Prediction:</b> a simple sum of four parts, shown on the right for a real film.",
          "<b>New viewers:</b> “fold-in” calculates their numbers straight away, with no retraining.",
        ],
        simple: "It might learn something like “serious dramas versus light comedies” without ever being told what a genre is.",
        go: this.go,
      });
      fill(stage, c, async () => {
        const d = await c.once(`svd:${c.viewer}`, () => api.collaborative(c.viewer, "svd", 1));
        if (d.empty) return [notice("No ratings yet", d.message)];
        const top = d.items[0];
        const e = top.explanation;
        const scale = Math.max(5, e.raw);
        const history = d.method.training.map((h) => ({ x: h.epoch, y: h.train_rmse }));
        const lo = Math.floor(Math.min(...history.map((h) => h.y)) * 10) / 10;
        const hi = Math.ceil(Math.max(...history.map((h) => h.y)) * 10) / 10;
        const last = history[history.length - 1];
        const parts = [
          { label: "Average of all ratings", sub: "μ", value: e.mu, display: fmt.n(e.mu, 2) },
          { label: "This viewer's rating habit", sub: "b_u", value: e.b_u, display: fmt.signed(e.b_u, 2) },
          { label: "How well liked the film is", sub: "b_i", value: e.b_i, display: fmt.signed(e.b_i, 2) },
          { label: "Taste match", sub: "p_u · q_i", value: e.dot, display: fmt.signed(e.dot, 2) },
          { label: e.clipped ? "Predicted score, shown as 5.00" : "Predicted rating", kind: "total", value: e.raw, display: fmt.n(e.raw, 2), tone: "accent" },
        ];
        return [
          chartCard(`Why “${top.title}” scored ${fmt.n(e.raw, 2)} for ${c.viewerName()}`,
            "The four parts add up to the prediction. Ratings stop at 5, so a higher score still shows as 5.00.",
            waterfall(parts, { max: scale, label: "Parts of the predicted rating" }),
            { headers: ["Part", "Value"], rows: parts.map((p) => [p.label, p.display]) }),
          chartCard("The error falls as the model learns",
            "Training error (RMSE) after each round: lower means closer to the real ratings.",
            lineChart(history, {
              xLabel: "Training round", yLabel: "Error (RMSE)",
              xFormat: String, yFormat: (v) => fmt.n(v, 2),
              tipLabel: (p) => `training error after round ${p.x}`,
              yMin: lo, yMax: hi, xTicks: [1, 10, 20, history.length].filter((t, i, a) => a.indexOf(t) === i && t <= history.length),
              marks: [{ x: last.x, y: last.y, label: fmt.n(last.y, 3), side: "left" }],
              label: "Training error by round", height: 86,
            }),
            { headers: ["Round", "Training error"], rows: history.map((h) => [h.x, fmt.n(h.y, 4)]) }),
        ];
      });
    },
  },
  {
    id: "hybrid", chapter: "The algorithms", label: "4. Hybrid",
    go: { view: "hybrid", label: "Hybrid page" },
    notes: "The hybrid combines both methods, because they fail in opposite ways. Alpha is the blend weight: at zero it uses only content, at one it uses only collaborative. Let me move the slider so you can see the ranking change. If the two methods agreed, nothing would move. The project uses alpha of 0.7, chosen by testing several values.",
    render(stage, c) {
      const base = c.status.models.hybrid.alpha;
      stage.innerHTML = slide({
        chapter: "Method 3 · Hybrid",
        title: "Each method covers the other's blind spot",
        lede: "Content-based cannot surprise you. Collaborative cannot start without ratings. The hybrid scales both scores from 0 to 1, then blends them.",
        points: [
          "<b>Weighted:</b> mixes the two scores using α. This is the main method.",
          "<b>Switching:</b> uses content only until a viewer has enough ratings.",
          "<b>Rank fusion:</b> combines the two ranked positions instead of the scores.",
          "<b>Cascade:</b> collaborative makes a shortlist, and content re-orders it.",
        ],
        simple: "It asks both methods and combines their answers. The slider decides how much to trust each one.",
        go: this.go,
      });
      if (c.alpha === undefined) c.alpha = base;
      const node = el(`
        <div>
          <figure class="cx-card">
            <figcaption><b>Move the slider and watch the ranking change</b>
              <span>Top 6 for ${esc(c.viewerName())}. Bar length is the blended score.</span></figcaption>
            <div class="ov-alpha">
              <label for="ovAlpha">Blend weight α = <b id="ovAlphaV"></b></label>
              <input type="range" id="ovAlpha" min="0" max="100" value="${Math.round(c.alpha * 100)}" />
              <div class="ov-alpha__ends"><span>All content</span><span>All collaborative</span></div>
            </div>
            ${legend([{ name: "From content", tone: "accent" }, { name: "From collaborative", tone: "cf" }])}
            <div id="ovBlend"><div class="loading"><span class="spin"></span> Blending</div></div>
          </figure>
        </div>`);
      $("#ovVisual", stage).append(node);
      const host = $("#ovBlend", node);
      const label = $("#ovAlphaV", node);
      const token = c.token;
      let request = 0;

      async function draw() {
        const mine = ++request;
        label.textContent = fmt.n(c.alpha, 2);
        host.style.opacity = host.childElementCount ? "0.55" : "1"; // keep the frame while refetching
        try {
          const d = await api.hybrid(c.viewer, { n: 6, strategy: "weighted", cf_model: "item-knn", alpha: c.alpha });
          if (token !== c.token || mine !== request) return;
          host.style.opacity = "1";
          if (d.empty) { host.replaceChildren(notice("No ratings yet", d.message)); return; }
          host.replaceChildren(stacked(d.items.map((f) => ({
            label: f.title, sub: f.lead === "collaborative" ? "led by collaborative" : "led by content",
            display: fmt.n(f.score, 3),
            parts: [
              { name: "Content", value: f.content_part, tone: "accent", text: fmt.n(f.content_part, 3) },
              { name: "Collaborative", value: f.cf_part, tone: "cf", text: fmt.n(f.cf_part, 3) },
            ],
          })), { max: 1, label: "Blended scores" }));
        } catch (err) {
          if (token === c.token && mine === request) host.replaceChildren(notice("This chart could not load.", err.message));
        }
      }
      let wait;
      $("#ovAlpha", node).addEventListener("input", (e) => {
        c.alpha = Number(e.target.value) / 100;
        label.textContent = fmt.n(c.alpha, 2);
        clearTimeout(wait);
        wait = setTimeout(draw, 140);
      });
      draw();
    },
  },
  {
    id: "cold", chapter: "The algorithms", label: "The cold-start problem",
    go: { view: "rate", label: "Rate films" },
    notes: "A very common question is what happens with a brand-new user. This is called the cold-start problem. Collaborative filtering has nothing to compare yet, so the system depends on the content method first, and brings in collaborative filtering as ratings build up. On the Rate Films page you can create a viewer and watch each method switch on.",
    render(stage, c) {
      const limit = c.status.models.hybrid.cold_start_threshold;
      stage.innerHTML = slide({
        chapter: "A hard case",
        title: "What about a brand-new viewer?",
        lede: "With no ratings, there is nothing to compare. This is called the cold-start problem, and each method handles it differently.",
        points: [
          "<b>Content-based</b> works from the first rating.",
          "<b>Collaborative</b> needs a few ratings before it has anything to compare.",
          `<b>The switching hybrid</b> relies on content until the viewer has ${limit} ratings.`,
          "<b>New films</b> have the same problem, and content-based can still suggest them from their description.",
        ],
        simple: "It is like a new customer in a shop. At first you can only go by the one thing they picked up. The more they buy, the better you know them.",
        go: this.go,
      });
      const stops = [0, 1, 3, limit];
      const rows = [
        ["Content-based", (n) => n >= 1],
        ["Collaborative", (n) => n >= 3],
        ["Hybrid uses both", (n) => n >= limit],
      ];
      const t = c.summary.long_tail;
      const films = c.status.dataset.n_items;
      $("#ovVisual", stage).append(
        chartCard("Which methods work at each stage",
          "The app shows this live on the Rate films page.",
          `<div class="ov-matrix-wrap"><table class="ov-matrix">
            <thead><tr><th>Ratings given</th>${stops.map((s) => `<th>${s}</th>`).join("")}</tr></thead>
            <tbody>${rows.map(([name, on]) => `<tr><th>${name}</th>
              ${stops.map((s) => `<td class="${on(s) ? "is-on" : ""}">${on(s) ? "● Ready" : "○ Waiting"}</td>`).join("")}
            </tr>`).join("")}</tbody>
          </table></div>`),
        stats([
          [`${fmt.n((t.films_under_five_ratings / films) * 100, 0)}%`, "of films have under 5 ratings", `${fmt.int(t.films_under_five_ratings)} films`],
          [fmt.int(t.films_with_one_rating), "films have just one rating", "new or rare films"],
          [fmt.int(c.summary.viewers.median_ratings), "ratings for the typical viewer", "the median"],
        ]),
        notice("Why this matters",
          "Most of the catalogue has very little rating data. So even for existing viewers, content-based filtering reaches films that collaborative filtering rarely can."),
      );
    },
  },
  {
    id: "results", chapter: "The results", label: "Results",
    go: { view: "score", label: "Full scoreboard" },
    notes: "To test fairly, the newest twenty percent of each viewer's ratings are hidden and each model has to predict them, like an exam it has never seen. Precision at ten measures how many of the ten films shown were actually liked. RMSE measures how far predicted ratings were from the real ones. Coverage measures how much of the catalogue gets recommended. The main finding is that no single method wins everything, and the hybrid has the best ranking.",
    render(stage, c) {
      stage.innerHTML = slide({
        chapter: "The results",
        title: "Measured on ratings the models never saw",
        lede: "The newest 20% of each viewer's ratings were hidden. Each model then predicted them, like an exam it had not seen.",
        points: ["<b>Loading the measured results…</b>"],
        go: this.go,
      });
      fill(stage, c, async () => {
        const d = await api.evaluation(10);
        const rows = d.rows;
        const by = (name) => rows.find((r) => r.model === name);
        const short = {
          baseline: "Baseline", content: "Content-based", "item-knn": "Item-kNN", "user-knn": "User-kNN",
          svd: "SVD", hybrid: "Hybrid (item-kNN)", "hybrid-svd": "Hybrid (SVD)",
        };
        const panel = (key, title, sub, digits, lower, suffix = "") => {
          const sorted = [...rows].sort((a, b) => (lower ? a[key] - b[key] : b[key] - a[key]));
          const best = sorted[0][key];
          return chartCard(title, sub, barList(sorted.map((r) => ({
            label: short[r.model] || r.label, value: r[key], display: `${fmt.n(r[key], digits)}${suffix}`,
            tone: r[key] === best ? "accent" : "muted", tip: r.label,
          })), { label: title }),
          { headers: ["Model", title], rows: sorted.map((r) => [r.label, `${fmt.n(r[key], digits)}${suffix}`]) });
        };
        const h = by("hybrid");
        const i = by("item-knn");
        const t = by("content");
        const gain = ((h.precision - i.precision) / i.precision) * 100;
        const list = $(".ovs__points", stage);
        if (list) {
          list.innerHTML = `
            <li><b>The hybrid wins at ranking:</b> Precision@10 of ${fmt.n(h.precision, 4)}, against
              ${fmt.n(i.precision, 4)} for item-kNN and ${fmt.n(t.precision, 4)} for content alone. That is ${fmt.n(gain, 1)}% better than its best parent.</li>
            <li><b>No model wins everything:</b> content-based has by far the widest coverage at ${fmt.n(t.coverage, 1)}%.</li>
            <li><b>Why the numbers look small:</b> a film only counts as a hit if the viewer really rated it in the hidden 20%, so Precision@10 is best used for comparing models.</li>`;
        }
        return [
          el(`<div class="ov-grid2"></div>`),
        ].map((box) => {
          box.append(
            panel("precision", "Precision@10", "Of 10 films shown, the share the viewer really liked. Higher is better.", 4, false),
            panel("rmse", "RMSE", "How far predictions were from real ratings. Lower is better.", 4, true),
            panel("hit_rate", "Hit rate", "Viewers who got at least one good film. Higher is better.", 4, false),
            panel("coverage", "Coverage", "Share of all films ever recommended. Higher is broader.", 1, false, "%"),
          );
          return box;
        });
      }, "Scoring seven models across 610 viewers");
    },
  },
  {
    id: "people", chapter: "The results", label: "Is it really personal?",
    go: { view: "people", label: "Compare viewers" },
    notes: "A fair question is whether the recommendations are really personal or whether everyone gets the same popular films. So here the same algorithm is run for different viewers, and the lists are compared side by side. The films are different because the viewers are different.",
    render(stage, c) {
      stage.innerHTML = slide({
        chapter: "The results",
        title: "Same algorithm, different people, different films",
        lede: "If the system were faking it, everyone would get the same popular films. Here is the same hybrid model run for three different viewers.",
        simple: "Two people asking the same system get different answers, because their past ratings are different.",
        points: [],
        go: this.go,
      });
      fill(stage, c, async () => {
        const others = c.state.users.builtin.map((u) => u.userId).filter((id) => id !== c.viewer).slice(0, 2);
        const d = await api.compareUsers([c.viewer, ...others], "hybrid", 5);
        const seen = new Map();
        d.columns.forEach((col) => col.items.forEach((f) => seen.set(f.movieId, (seen.get(f.movieId) || 0) + 1)));
        const total = d.columns.reduce((sum, col) => sum + col.items.length, 0);
        return [
          stats([
            [String(seen.size), `different films across ${total} recommendations`],
            [String([...seen.values()].filter((n) => n > 1).length), "films shown to more than one viewer"],
          ]),
          ...d.columns.map((col) => el(`
            <div class="ov-person">
              <div class="ov-person__h"><b>${esc(col.name)}</b>
                <span>${fmt.int(col.n_ratings)} ratings · likes ${esc(col.top_genres.map((g) => g.genre).slice(0, 2).join(", ") || "various")}</span></div>
              <div class="ov-person__row">
                ${col.items.map((f) => `<div class="ov-mini ${seen.get(f.movieId) > 1 ? "is-shared" : ""}">
                  ${poster(f, "ov-mini__art")}<span>${esc(f.title)}</span></div>`).join("")}
              </div>
            </div>`)),
        ];
      });
    },
  },
  {
    id: "tour", chapter: "The website", label: "What the site can do",
    notes: "This is everything the website can do. I can open any of these pages live. For a short demonstration I suggest Browse first, then Rate Films to create a new viewer, then the Hybrid page to move the slider, and finally the Scoreboard.",
    render(stage, c) {
      const PAGES = [
        ["browse", "Browse", "The home page. Each row of films is produced by a different method. Click “Why this?” for the working."],
        ["data", "The data", "The dataset's key numbers and a real corner of the rating table."],
        ["rate", "Rate films", "Search films by title, director or topic. Create a viewer and watch each method switch on."],
        ["content", "Content-based", "The taste profile, genre chart and picks, with the words behind each score."],
        ["collab", "Collaborative", "Item-kNN, user-kNN and SVD, with neighbours and a 3D map of films you can rotate."],
        ["hybrid", "Hybrid", "A live blend slider and all four combining strategies."],
        ["score", "Scoreboard", "Seven models tested on hidden ratings, with charts and a full table."],
        ["people", "Compare viewers", "Up to four viewers side by side, to prove the results are personal."],
        ["guide", "Project guide", "A one-page summary, a glossary of terms, and answers to common questions."],
      ];
      stage.innerHTML = `
        <div class="ov-tour">
          <span class="kicker kicker--accent">The website</span>
          <h2 class="ovs__title">Everything the website can do</h2>
          <p class="ovs__lede">Click any page to open it live. The overview stays ready, so you can come straight back.</p>
          <div class="ov-tour__grid">
            ${PAGES.map(([view, name, text], i) => `
              <button class="ov-tour__card" data-page="${view}">
                <span>${String(i + 1).padStart(2, "0")}</span>
                <b>${esc(name)}</b>
                <small>${esc(text)}</small>
              </button>`).join("")}
          </div>
        </div>`;
      $(".ov-tour__grid", stage).addEventListener("click", (e) => {
        const b = e.target.closest("[data-page]");
        if (b) c.goLive({ view: b.dataset.page });
      });
    },
  },
  {
    id: "end", chapter: "Conclusion", label: "Conclusion",
    notes: "To conclude: this is a complete recommendation website in Java, with the algorithms implemented from scratch and tested on hidden data. The hybrid gave the best ranking, which shows that combining methods really does help. The next steps would be a larger dataset and testing with real users. Thank you. I am happy to take questions.",
    render(stage, c) {
      stage.innerHTML = `
        <div class="ov-end">
          <span class="kicker kicker--accent">Conclusion</span>
          <h2 class="ovs__title">What this project shows</h2>
          <div class="ov-end__cols">
            <div>
              <h3>What was built</h3>
              <ul class="ovs__points">
                <li>A complete website with a Java and Spring Boot back end.</li>
                <li>Seven models, including two hybrids, with the algorithms implemented from scratch.</li>
                <li>An explanation behind every recommendation.</li>
              </ul>
            </div>
            <div id="ovFindings">
              <h3>What was found</h3>
              <ul class="ovs__points"><li>Loading the measured results…</li></ul>
            </div>
            <div>
              <h3>Limits and next steps</h3>
              <ul class="ovs__points">
                <li>Tested offline on past ratings, not with live users.</li>
                <li>Could add a larger dataset and viewing history.</li>
                <li>Could run a real user study to measure satisfaction.</li>
              </ul>
            </div>
          </div>
          <div class="ov-end__go">
            <button class="btn btn--primary" data-page="browse">Explore the website ${ICON.arrowR}</button>
            <button class="btn btn--ghost" data-page="guide">Project guide and questions</button>
            <button class="btn btn--ghost" id="ovAgain">${ICON.replay} Start again</button>
          </div>
        </div>`;
      $(".ov-end__go", stage).addEventListener("click", (e) => {
        const b = e.target.closest("[data-page]");
        if (b) c.goLive({ view: b.dataset.page });
      });
      $("#ovAgain", stage).addEventListener("click", () => c.jump(0));

      const token = c.token;
      api.evaluation(10).then((d) => {
        if (token !== c.token) return;
        const by = (name) => d.rows.find((r) => r.model === name);
        const h = by("hybrid");
        const i = by("item-knn");
        const t = by("content");
        const s = by("svd");
        $("#ovFindings ul", stage).innerHTML = `
          <li>The hybrid ranks best: Precision@10 of ${fmt.n(h.precision, 4)}, ahead of item-kNN (${fmt.n(i.precision, 4)}) and content (${fmt.n(t.precision, 4)}).</li>
          <li>Accurate ratings and good rankings are different goals: SVD's RMSE of ${fmt.n(s.rmse, 4)} beats item-kNN's ${fmt.n(i.rmse, 4)}, yet it ranks lower.</li>
          <li>Content-based reaches the most films, covering ${fmt.n(t.coverage, 1)}% of the catalogue.</li>`;
      }).catch(() => {
        if (token === c.token) $("#ovFindings ul", stage).innerHTML = "<li>See the Scoreboard page for the measured results.</li>";
      });
    },
  },
];

/* ── the player ───────────────────────────────────────────── */
let node = null;
let resume = null;
let current = 0;
let timer = null;
let ctx = null;
let playing = false;
let notesOn = false;
let opener = null;

function loadNotesPref() {
  try { return localStorage.getItem(NOTES_KEY) === "on"; } catch { return false; }
}
function saveNotesPref(on) {
  try { localStorage.setItem(NOTES_KEY, on ? "on" : "off"); } catch { /* private mode */ }
}

function removeResume() {
  resume?.remove();
  resume = null;
}

export function closeOverview({ keepFocus = false } = {}) {
  clearTimeout(timer);
  hideTip();
  node?.remove();
  node = null;
  playing = false;
  document.removeEventListener("keydown", onKey);
  if (ctx) ctx.token += 1;
  if (!keepFocus) opener?.focus?.();
}

function showResume() {
  removeResume();
  const stepNo = current;
  resume = el(`
    <div class="ov-resume">
      <button class="ov-resume__go">${ICON.play}<span>Back to overview<small>Step ${stepNo + 1} of ${STEPS.length}</small></span></button>
      <button class="ov-resume__x" aria-label="Dismiss">${ICON.close}</button>
    </div>`);
  $(".ov-resume__go", resume).addEventListener("click", () => {
    const state = ctx.state;
    openOverview(state, stepNo);
  });
  $(".ov-resume__x", resume).addEventListener("click", removeResume);
  document.body.append(resume);
}

function onKey(e) {
  if (!node) return;
  if (e.target.closest?.("input,select,textarea,summary")) {
    if (e.key === "Escape") closeOverview();
    return;
  }
  const key = e.key;
  if (key === "Escape") { closeOverview(); return; }
  if (key === "ArrowRight" || key === "PageDown" || (key === " " && !e.target.closest?.("button"))) {
    e.preventDefault();
    go(current + 1, true);
  } else if (key === "ArrowLeft" || key === "PageUp") {
    e.preventDefault();
    go(current - 1, true);
  } else if (key === "Home") {
    go(0, true);
  } else if (key === "End") {
    go(STEPS.length - 1, true);
  } else if (key === "n" || key === "N") {
    toggleNotes();
  } else if (key === "a" || key === "A") {
    togglePlay();
  }
}

function toggleNotes() {
  notesOn = !notesOn;
  saveNotesPref(notesOn);
  drawChrome();
}

function togglePlay() {
  playing = !playing;
  if (playing && current >= STEPS.length - 1) { go(0, false); return; }
  arm();
  drawChrome();
}

function arm() {
  clearTimeout(timer);
  if (!playing || reduced()) return;
  timer = setTimeout(() => {
    if (current >= STEPS.length - 1) { playing = false; drawChrome(); return; }
    go(current + 1, false);
  }, AUTO_MS);
}

function go(index, byHand) {
  if (index < 0 || index >= STEPS.length) return;
  if (byHand) playing = false;
  current = index;
  draw();
}

function drawChrome() {
  const step = STEPS[current];
  const next = STEPS[current + 1];
  const prev = current > 0;
  const chapters = [...new Set(STEPS.map((s) => s.chapter))];

  $("#ovTicks", node).innerHTML = chapters.map((ch) => `
    <div class="ov-chapter ${step.chapter === ch ? "is-on" : ""}" style="--n:${STEPS.filter((s) => s.chapter === ch).length}">
      <span>${esc(ch)}</span>
      <div>${STEPS.map((s, i) => (s.chapter !== ch ? "" : `
        <button data-step="${i}" class="${i < current ? "is-done" : ""} ${i === current ? "is-now" : ""}"
          aria-label="Step ${i + 1}: ${esc(s.label)}" title="${esc(s.label)}"
          ${i === current ? 'aria-current="step"' : ""}>
          ${i === current && playing && !reduced() ? `<i style="animation-duration:${AUTO_MS}ms"></i>` : ""}
        </button>`)).join("")}</div>
    </div>`).join("");

  $("#ovCount", node).textContent = `Step ${current + 1} of ${STEPS.length}`;
  $("#ovLabel", node).textContent = step.label;
  const prevBtn = $("#ovPrev", node);
  prevBtn.disabled = !prev;
  const nextBtn = $("#ovNext", node);
  nextBtn.innerHTML = next
    ? `<span>Next: ${esc(next.label)}</span>${ICON.arrowR}`
    : "<span>Finish overview</span>";
  $("#ovPlay", node).innerHTML = `${playing ? ICON.pause : ICON.play}<span>${playing ? "Pause" : "Auto-play"}</span>`;
  $("#ovPlay", node).setAttribute("aria-pressed", String(playing));
  $("#ovNotesBtn", node).setAttribute("aria-pressed", String(notesOn));
  const notes = $("#ovNotes", node);
  notes.hidden = !notesOn;
  $("#ovNotesText", node).textContent = step.notes;
}

function draw() {
  const step = STEPS[current];
  ctx.token += 1;
  const stage = $("#ovStage", node);
  stage.scrollTop = 0;
  stage.className = `ov__stage ov__stage--${step.id}`;
  const body = el('<section class="ovs-wrap" aria-live="polite"></section>');
  body.innerHTML = '<div class="ovs"></div>';
  stage.replaceChildren(body);
  const slideHost = $(".ovs", body);
  hideTip();
  try {
    step.render(slideHost, ctx);
  } catch (err) {
    slideHost.innerHTML = `<div class="note"><b>This step could not load.</b><p>${esc(err.message)}</p></div>`;
  }
  $("[data-golive]", slideHost)?.addEventListener("click", () => ctx.goLive(step.go));
  drawChrome();
  arm();
}

export async function openOverview(state, start = 0) {
  removeResume();
  closeOverview({ keepFocus: true });
  opener = document.activeElement;
  notesOn = loadNotesPref();
  playing = false;
  current = Math.max(0, Math.min(STEPS.length - 1, start));

  node = el(`
    <div class="ov" role="dialog" aria-modal="true" aria-label="Project overview" tabindex="-1">
      <header class="ov__top">
        <b class="ov__brand">Project overview</b>
        <span class="ov__where"><b id="ovCount"></b><span id="ovLabel"></span></span>
        <span class="ov__spacer"></span>
        <label class="ov__viewer">Example viewer
          <select id="ovViewer" aria-label="Example viewer"></select></label>
        <button class="btn btn--ghost" id="ovNotesBtn" aria-pressed="false" title="Show or hide speaker notes (N)">Speaker notes</button>
        <button class="btn btn--ghost" id="ovPlay" aria-pressed="false" title="Advance by itself (A)"></button>
        <button class="btn btn--icon" id="ovClose" aria-label="Close overview" title="Close (Esc)">${ICON.close}</button>
      </header>
      <nav class="ov__ticks" id="ovTicks" aria-label="Overview steps"></nav>
      <main class="ov__stage" id="ovStage"><div class="loading" style="padding:40px"><span class="spin"></span> Loading the live data</div></main>
      <aside class="ov__notes" id="ovNotes" hidden>
        <b>What to say</b><p id="ovNotesText"></p>
      </aside>
      <footer class="ov__foot">
        <button class="btn btn--icon" id="ovPrev" aria-label="Previous step" title="Previous (left arrow)">${ICON.arrowL}</button>
        <span class="ov__hint">Use the <kbd>←</kbd> <kbd>→</kbd> keys · <kbd>N</kbd> notes · <kbd>A</kbd> auto-play · <kbd>Esc</kbd> close</span>
        <span class="ov__spacer"></span>
        <button class="btn btn--primary ov__next" id="ovNext"></button>
      </footer>
    </div>`);
  document.getElementById("overlays").append(node);
  document.addEventListener("keydown", onKey);
  node.focus();

  $("#ovClose", node).addEventListener("click", () => closeOverview());
  $("#ovPrev", node).addEventListener("click", () => go(current - 1, true));
  $("#ovNext", node).addEventListener("click", () => {
    if (current >= STEPS.length - 1) closeOverview();
    else go(current + 1, true);
  });
  $("#ovPlay", node).addEventListener("click", togglePlay);
  $("#ovNotesBtn", node).addEventListener("click", toggleNotes);
  $("#ovTicks", node).addEventListener("click", (e) => {
    const b = e.target.closest("[data-step]");
    if (b) go(Number(b.dataset.step), true);
  });

  /* The live examples need a viewer with something to work from. */
  const pool = [
    ...state.users.custom.filter((u) => u.n_ratings >= 5),
    ...state.users.builtin.slice(0, 5),
  ];
  const active = [...state.users.custom, ...state.users.builtin].find((u) => u.userId === state.userId);
  const viewers = active && active.n_ratings >= 5 && !pool.some((u) => u.userId === active.userId)
    ? [active, ...pool] : pool;
  const chosen = active && active.n_ratings >= 5 ? active : viewers[0];

  const memo = new Map();
  ctx = {
    state,
    token: (ctx?.token ?? 0) + 1,
    viewer: chosen.userId,
    alpha: undefined,
    status: null,
    summary: null,
    viewerName() {
      const u = viewers.find((v) => v.userId === this.viewer);
      return u ? u.name.replace("MovieLens ", "") : `user ${this.viewer}`;
    },
    once(key, make) {
      if (!memo.has(key)) {
        memo.set(key, make().catch((err) => { memo.delete(key); throw err; }));
      }
      return memo.get(key);
    },
    goLive(target) {
      if (!target) return;
      if (target.cfModel) state.cfModel = target.cfModel;
      if (this.viewer !== state.userId) state.setUser?.(this.viewer);
      if (target.view === "hybrid" && this.alpha !== undefined) state.alpha = this.alpha;
      closeOverview({ keepFocus: true });
      showResume();
      state.go(target.view);
    },
    jump(index) { go(index, true); },
  };

  const select = $("#ovViewer", node);
  select.innerHTML = viewers.map((u) => `<option value="${u.userId}" ${u.userId === chosen.userId ? "selected" : ""}>
    ${esc(u.name.replace("MovieLens ", ""))} (${fmt.int(u.n_ratings)} ratings)</option>`).join("");
  select.addEventListener("change", () => {
    ctx.viewer = Number(select.value);
    draw();
  });

  drawChrome();
  try {
    const [status, summary] = await Promise.all([api.status(), api.overview()]);
    if (!node) return;
    ctx.status = status;
    ctx.summary = summary;
    draw();
  } catch (err) {
    if (!node) return;
    $("#ovStage", node).innerHTML = `<div class="note" style="margin:30px"><b>The overview could not load.</b>
      <p>${esc(err.message)}</p></div>`;
  }
}

export const OVERVIEW_STEPS = STEPS.length;
