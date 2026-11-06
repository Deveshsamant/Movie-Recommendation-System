/* The project guide: a summary to present from, a glossary, and common questions.
   Results come from the live scoreboard, so they always match the rest of the site. */

import { api } from "./api.js?v=16";
import { $, $$, el, esc, fmt, ICON, loading } from "./ui.js?v=16";

const GLOSSARY = [
  ["Recommender system", "Software that predicts which items a person will like, then shows the best ones first.", "This website, for films."],
  ["Rating matrix", "A table with one row per viewer and one column per film. Each cell holds a rating, or is empty.", "610 rows × 9,724 columns."],
  ["Sparsity", "How empty the rating matrix is.", "About 98.3% of the cells are empty."],
  ["Content-based filtering", "Recommends films whose description matches what the viewer already liked.", "Uses genres, tags, keywords, cast and directors."],
  ["Collaborative filtering", "Recommends films using the rating patterns of many viewers. It never reads what the film is about.", "Item-kNN, user-kNN and SVD."],
  ["Hybrid recommender", "Combines content-based and collaborative scores to cover each method's weaknesses.", "Four strategies: weighted, switching, rank fusion, cascade."],
  ["TF-IDF", "Term Frequency × Inverse Document Frequency. A word gets more weight when it is common in this film but rare across all films.", "“Time travel” says more about a film than “Drama”."],
  ["Cosine similarity", "Measures how closely two lists of numbers point the same way. 1 means a perfect match and 0 means nothing in common.", "Compares a film with the viewer's taste profile."],
  ["Taste profile", "One list of numbers summing up a viewer's taste. Films rated above their average pull it closer, and films below push it away.", "Known as a Rocchio profile."],
  ["kNN (k nearest neighbours)", "Predicts from the k most similar items or viewers.", "This project uses k = 40."],
  ["Mean-centring", "Subtracting a viewer's average rating from each rating, so that generous and harsh viewers can be compared fairly.", "A 4 from a harsh viewer can mean more than a 4 from a generous one."],
  ["Significance shrinkage", "Reduces similarities that are based on very few shared ratings, because they are unreliable.", "similarity × n / (n + 25)"],
  ["Matrix factorisation (SVD)", "Breaks the rating matrix into a viewer table and a film table of hidden factors. Multiplying them back fills in the blanks.", "rating = μ + b_u + b_i + p_u · q_i"],
  ["Latent factor", "A hidden taste dimension the model learns by itself. Nobody names it.", "This project learns 50."],
  ["Stochastic gradient descent (SGD)", "Training by small repeated corrections: predict, measure the error, adjust the numbers, and repeat.", "30 rounds here."],
  ["Fold-in", "Working out a new viewer's hidden factors straight away, without retraining the whole model.", "How a viewer you create gets SVD results at once."],
  ["Alpha (α)", "The blend weight of the hybrid. 0 means all content, and 1 means all collaborative.", "This project uses α = 0.70."],
  ["Cold start", "The difficulty of recommending for a new viewer or a new film that has few or no ratings.", "Content-based filtering handles it best."],
  ["Long tail", "The pattern where a few films get most of the ratings, and most films get very few.", "See the Long tail step in the overview."],
  ["Temporal split", "Testing on each viewer's newest ratings and training on the older ones, so a model cannot use the future to predict the past.", "80% training, 20% test."],
  ["RMSE", "Root Mean Squared Error: the typical size of a prediction's error, in stars. Large errors count extra. Lower is better.", "An RMSE of 0.9 means predictions are typically about 0.9 stars off."],
  ["MAE", "Mean Absolute Error: the average size of a prediction's error, in stars. Lower is better.", "Simpler to read than RMSE."],
  ["Precision@10", "Of the 10 films shown, the share the viewer really liked (rated 4 or more in the hidden ratings). Higher is better.", "0.09 means about 1 good film in every 10 shown."],
  ["Recall@10", "Of all the films the viewer really liked, the share found in the top 10. Higher is better.", "Measures how much was found."],
  ["NDCG@10", "Like precision, but gives more credit when the good films are near the top of the list. Higher is better.", "Measures the order as well as the content."],
  ["Hit rate", "The share of viewers who got at least one good film in their top 10. Higher is better.", ""],
  ["Coverage", "The share of all films that were ever recommended to anyone. Higher means a broader system.", "Content-based has the highest."],
  ["Novelty", "How unusual the recommended films are. A higher score means less famous films.", "Calculated from how often each film is rated."],
];

const QUESTIONS = (m) => [
  ["What problem does this project solve?", "People cannot look through thousands of films. The system predicts which unseen films each person will enjoy and shows them in order, with an explanation for each one."],
  ["Which dataset did you use, and why?", `MovieLens latest-small, from GroupLens at the University of Minnesota, with ${m.ratings} ratings from ${m.users} viewers on ${m.items} films. It is a standard public research dataset, so the ratings are real and the results can be compared. TMDB adds posters, cast, directors and keywords.`],
  ["Why three approaches rather than one?", "They fail in opposite ways. Content-based cannot surprise the viewer, and collaborative cannot work without ratings. Building both, and a hybrid, lets the project measure that trade-off instead of just claiming it."],
  ["What is the difference between content-based and collaborative?", "Content-based reads information about the film, and never looks at other people. Collaborative looks only at who rated what, and never reads anything about the film."],
  ["Why is the hybrid better?", m.hybridLine],
  ["Why is Precision@10 so low? Is that bad?", "No. A film only counts as a hit if the viewer really rated it highly in the hidden test data. A film they would love but have never rated counts as a miss, so the figure is an underestimate. It is most useful for comparing models with each other."],
  ["How do you know the results are fair?", "Each model is trained on the older 80% of each viewer's ratings and tested on the newest 20%, which it never saw. A split by time is more honest than a random split, because a model cannot use a viewer's future to predict their past."],
  ["What is the cold-start problem, and how is it handled?", "A new viewer or film has few or no ratings to learn from. Content-based works from the first rating and can describe a new film. The switching hybrid uses content until the viewer has 8 ratings. SVD uses fold-in, so a new viewer gets results without retraining."],
  ["Which model is the best?", m.bestLine],
  ["Why does SVD have the best accuracy among single models but rank lower?", "Predicting a rating accurately and choosing the best ten films are different jobs. RMSE rewards being close on every film, while Precision@10 only cares about the top of the list. This is why the project measures both."],
  ["How was α = 0.70 chosen?", "Several values of alpha from 0 to 1 were tested, and 0.70 was chosen because it scored well on NDCG@10. You can move the slider on the Hybrid page to see how the ranking changes."],
  ["Does it use a machine-learning library?", "No. TF-IDF, kNN, SVD and the evaluation metrics are implemented from scratch in Java. Apache Commons Math is used only for two standard linear-algebra operations: solving equations and an eigendecomposition for the 3D map."],
  ["What technologies are used, and why?", "Java 17 and Spring Boot for the server, Maven for building, and plain HTML, CSS and JavaScript for the pages. Spring Boot provides a web server and JSON handling, which keeps the code focused on the recommendation logic."],
  ["How does the 3D map work?", "SVD gives each film 50 numbers. Principal Component Analysis (PCA) reduces them to the 3 directions with the most variation, so each film can be drawn as a point in 3D."],
  ["What are the limitations?", "The model is tested offline on past ratings, not with real users. The dataset is small and its ratings are mostly high. It also does not use viewing history or update the serving models as new ratings arrive. Only a viewer's own profile updates."],
  ["How could this be improved?", "Use a larger dataset, add viewing history, retrain regularly, try deep-learning models, and run a user study to see whether people actually prefer the recommendations."],
];

export async function guide(host, state) {
  host.innerHTML = `
    <span class="kicker kicker--outline">Project guide</span>
    <h1 class="display">Everything you need to explain this project</h1>
    <p class="lede">A summary to present from, a glossary of every technical term, and clear answers to the
      questions a teacher is likely to ask.</p>
    <div class="plain"><b>In simple words</b><p>This page is the project's notes. If anyone asks what a word
      means or why something was done, the answer is here.</p></div>
    <div style="display:flex;flex-wrap:wrap;gap:12px;align-items:center;margin-top:22px">
      <div class="seg" id="gTabs">
        <button data-tab="summary" class="is-on">Summary</button>
        <button data-tab="glossary">Glossary</button>
        <button data-tab="questions">Questions</button>
      </div>
      <button class="btn btn--primary" id="gStart">${ICON.play} Start overview</button>
    </div>
    <div id="gBody"></div>`;

  $("#gStart", host).addEventListener("click", () => state.openOverview());
  const body = $("#gBody", host);
  body.append(loading("Reading the live results"));

  const [status, evaluation] = await Promise.all([api.status(), api.evaluation(10)]);
  const rows = evaluation.rows;
  const by = (name) => rows.find((r) => r.model === name);
  const h = by("hybrid");
  const hs = by("hybrid-svd");
  const i = by("item-knn");
  const t = by("content");
  const d = status.dataset;
  const m = {
    ratings: fmt.int(d.n_ratings),
    users: fmt.int(d.n_users),
    items: fmt.int(d.n_items),
    hybridLine: `Because it combines two signals that make different mistakes. On hidden test data its Precision@10 is ${fmt.n(h.precision, 4)}, against ${fmt.n(i.precision, 4)} for item-kNN alone and ${fmt.n(t.precision, 4)} for content alone, so it beats both of its parents.`,
    bestLine: `It depends on the question. For ranking, the content + item-kNN hybrid is best, with a Precision@10 of ${fmt.n(h.precision, 4)}. For predicting ratings, the content + SVD hybrid is best, with an RMSE of ${fmt.n(hs.rmse, 4)}. For reaching the most films, content-based is best, with ${fmt.n(t.coverage, 1)}% coverage.`,
  };

  const tabs = { summary: summaryTab, glossary: glossaryTab, questions: questionsTab };
  const show = (name) => {
    $$("#gTabs button", host).forEach((b) => b.classList.toggle("is-on", b.dataset.tab === name));
    body.replaceChildren();
    tabs[name](body, { state, status, evaluation, m, rows });
  };
  $("#gTabs", host).addEventListener("click", (e) => {
    const b = e.target.closest("[data-tab]");
    if (b) show(b.dataset.tab);
  });
  show("summary");
}

function summaryTab(body, { state, status, evaluation, rows }) {
  const d = status.dataset;
  const mo = status.models;
  const best = (key, lower) => (lower ? Math.min : Math.max)(...rows.map((r) => r[key]));
  const td = (r, key, digits, lower, suffix = "") =>
    `<td class="${r[key] === best(key, lower) ? "best" : ""}">${fmt.n(r[key], digits)}${suffix}</td>`;

  const node = el(`
    <div>
      <div class="g-grid">
        <section><h2 class="h3">Aim</h2>
          <p>To build a working website that recommends films to each person, explains every
            recommendation, and measures which recommendation method works best.</p></section>
        <section><h2 class="h3">Objectives</h2>
          <ul>
            <li>Build content-based, collaborative and hybrid recommenders.</li>
            <li>Show the calculation behind every suggestion.</li>
            <li>Handle new viewers who have few ratings.</li>
            <li>Compare every model fairly on hidden test data.</li>
          </ul></section>
        <section><h2 class="h3">Dataset</h2>
          <ul>
            <li>MovieLens latest-small: ${fmt.int(d.n_ratings)} ratings, ${fmt.int(d.n_users)} viewers, ${fmt.int(d.n_items)} films.</li>
            <li>TMDB details for ${fmt.int(status.content.tmdb_enriched)} films.</li>
            <li>Split: ${fmt.int(evaluation.split.train_ratings)} training, ${fmt.int(evaluation.split.test_ratings)} test ratings, by time.</li>
          </ul></section>
        <section><h2 class="h3">Methods</h2>
          <ul>
            <li>Content-based: TF-IDF and cosine similarity (${fmt.int(status.content.n_terms)} terms).</li>
            <li>Item-kNN and user-kNN: k = ${mo.item_knn.k}.</li>
            <li>SVD: ${mo.svd.n_factors} factors, ${mo.svd.n_epochs} training rounds.</li>
            <li>Hybrid: four strategies, with α = ${fmt.n(mo.hybrid.alpha, 2)}.</li>
          </ul></section>
        <section><h2 class="h3">Tools</h2>
          <ul>
            <li>Java 17, Spring Boot 3.5, Maven.</li>
            <li>Plain HTML, CSS and JavaScript.</li>
            <li>Apache Commons Math, JUnit tests.</li>
            <li>TMDB API for posters and film details.</li>
          </ul></section>
        <section><h2 class="h3">How to present it</h2>
          <ol id="gPlan">
            <li><button data-open="overview">Start overview</button> and walk through the steps.</li>
            <li><button data-view="rate">Rate films</button> as a new viewer, to show cold start.</li>
            <li><button data-view="hybrid">Hybrid</button>: move the slider.</li>
            <li><button data-view="score">Scoreboard</button>: read the results.</li>
          </ol></section>
      </div>

      <div class="sec-h"><div>
        <h2 class="h2">Measured results</h2>
        <p class="sub">Calculated by this program on ratings the models never saw. The best in each column is highlighted.</p>
      </div></div>
      <div class="tablewrap" style="margin-top:0">
        <table class="tbl">
          <thead><tr><th>Model</th><th>RMSE ↓</th><th>MAE ↓</th><th>Prec@10 ↑</th><th>NDCG@10 ↑</th><th>Hit rate ↑</th><th>Coverage ↑</th></tr></thead>
          <tbody>${rows.map((r) => `<tr>
            <td><span class="name">${esc(r.label)}</span></td>
            ${td(r, "rmse", 4, true)}${td(r, "mae", 4, true)}${td(r, "precision", 4)}${td(r, "ndcg", 4)}${td(r, "hit_rate", 4)}${td(r, "coverage", 1, false, "%")}
          </tr>`).join("")}</tbody>
        </table>
      </div>
    </div>`);
  $("#gPlan", node).addEventListener("click", (e) => {
    const b = e.target.closest("button");
    if (!b) return;
    if (b.dataset.open) state.openOverview(); else state.go(b.dataset.view);
  });
  body.append(node);
}

function glossaryTab(body) {
  const node = el(`
    <div>
      <div class="searchbar" style="margin-top:26px">
        <input type="text" id="gFilter" aria-label="Search the glossary" placeholder="Search for a word, such as TF-IDF or cold start" />
      </div>
      <dl class="g-terms" id="gTerms"></dl>
    </div>`);
  const list = $("#gTerms", node);
  const draw = (needle = "") => {
    const q = needle.trim().toLowerCase();
    const hits = GLOSSARY.filter((row) => row.join(" ").toLowerCase().includes(q));
    list.innerHTML = hits.length
      ? hits.map(([term, meaning, example]) => `<div>
          <dt>${esc(term)}</dt><dd>${esc(meaning)}${example ? `<small>${esc(example)}</small>` : ""}</dd></div>`).join("")
      : `<div class="loading">Nothing matches “${esc(needle)}”.</div>`;
  };
  draw();
  $("#gFilter", node).addEventListener("input", (e) => draw(e.target.value));
  body.append(node);
}

function questionsTab(body, { m }) {
  body.append(el(`
    <div class="g-qa">
      ${QUESTIONS(m).map(([q, a], i) => `
        <details ${i === 0 ? "open" : ""}><summary>${esc(q)}</summary><p>${esc(a)}</p></details>`).join("")}
    </div>`));
}
