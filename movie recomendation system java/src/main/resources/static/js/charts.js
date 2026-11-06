/* A small chart kit. Text stays real HTML, so it remains readable at any size;
   every value is reachable by hover, keyboard focus and a table view. */

import { el, esc } from "./ui.js?v=16";

/* ── tooltip ──────────────────────────────────────────────── */
let tipNode = null;

function tipEl() {
  if (!tipNode) {
    tipNode = document.createElement("div");
    tipNode.className = "cx-tip";
    tipNode.setAttribute("role", "status");
    tipNode.hidden = true;
    document.body.append(tipNode);
  }
  return tipNode;
}

export function showTip(x, y, value, label) {
  const node = tipEl();
  const strong = document.createElement("b");
  strong.textContent = value;
  const small = document.createElement("span");
  small.textContent = label;
  node.replaceChildren(strong, small);
  node.hidden = false;
  const box = node.getBoundingClientRect();
  node.style.left = `${Math.min(innerWidth - box.width - 10, Math.max(10, x + 14))}px`;
  node.style.top = `${Math.max(10, y - box.height - 12)}px`;
}

export function hideTip() {
  if (tipNode) tipNode.hidden = true;
}

function bindTip(node, value, label) {
  const show = (e) => {
    const r = node.getBoundingClientRect();
    showTip(e.clientX ?? r.left + r.width / 2, e.clientY ?? r.top, value, label);
  };
  node.addEventListener("pointermove", show);
  node.addEventListener("pointerleave", hideTip);
  node.addEventListener("focus", show);
  node.addEventListener("blur", hideTip);
}

/* ── axis helpers ─────────────────────────────────────────── */
function niceStep(raw) {
  const mag = 10 ** Math.floor(Math.log10(raw || 1));
  const n = raw / mag;
  return (n <= 1 ? 1 : n <= 2 ? 2 : n <= 5 ? 5 : 10) * mag;
}

function ticksFor(max, count = 4) {
  const step = niceStep(max / count);
  const out = [];
  for (let v = 0; v <= max + step * 0.001; v += step) out.push(Number(v.toFixed(6)));
  if (out[out.length - 1] < max) out.push(Number((out[out.length - 1] + step).toFixed(6)));
  return out;
}

/** Full numbers under 10,000, compact on an axis that reaches it: one style per axis. */
const axisFormat = (max) => (max >= 10000
  ? (v) => (v === 0 ? "0" : `${Math.round(v / 1000)}K`)
  : (v) => Number(v).toLocaleString());

const grid = (ticks, top, format) => ticks.map((t) => `
  <i class="cx-grid" style="bottom:${(t / top) * 100}%"><span>${esc(format(t))}</span></i>`).join("");

/* ── legend ───────────────────────────────────────────────── */
export const legend = (items) => `<div class="cx-legend">
  ${items.map((s) => `<span><i class="cx-tone--${s.tone}"></i>${esc(s.name)}</span>`).join("")}
</div>`;

/* ── ranked horizontal bars ───────────────────────────────── */
export function barList(rows, { max, label = "Bar chart" } = {}) {
  const top = max ?? Math.max(...rows.map((r) => r.value), 1e-9);
  const node = el(`<div class="cx-bars" role="list" aria-label="${esc(label)}"></div>`);
  rows.forEach((r, i) => {
    const row = el(`
      <div class="cx-bar" role="listitem" tabindex="0">
        <span class="cx-bar__l">${esc(r.label)}${r.sub ? `<small>${esc(r.sub)}</small>` : ""}</span>
        <span class="cx-bar__t">
          <i class="cx-bar__f cx-tone--${r.tone || "accent"}"
            style="--p:${Math.max(0, r.value / top)};animation-delay:${i * 35}ms"></i>
          <b class="cx-bar__v">${esc(r.display)}</b>
        </span>
      </div>`);
    bindTip(row, r.display, r.tip || r.label);
    node.append(row);
  });
  return node;
}

/* ── columns on one baseline ──────────────────────────────── */
export function columns(rows, { format = (v) => String(v), xTitle = "", label = "Column chart", height = 220 } = {}) {
  const peak = Math.max(...rows.map((r) => r.value), 1);
  const ticks = ticksFor(peak);
  const top = ticks[ticks.length - 1];
  const node = el(`
    <div class="cx-cols" role="group" aria-label="${esc(label)}">
      <div class="cx-plot" style="height:${height}px">
        ${grid(ticks, top, axisFormat(top))}
        <div class="cx-cols__row"></div>
      </div>
      <div class="cx-cols__x"></div>
      ${xTitle ? `<div class="cx-axis">${esc(xTitle)}</div>` : ""}
    </div>`);
  const row = node.querySelector(".cx-cols__row");
  const axis = node.querySelector(".cx-cols__x");
  rows.forEach((r, i) => {
    const isPeak = r.value === peak;
    const col = el(`
      <div class="cx-col" tabindex="0">
        <i class="cx-col__b" style="height:${(r.value / top) * 100}%;animation-delay:${i * 30}ms">
          ${isPeak ? `<b>${esc(format(r.value))}</b>` : ""}
        </i>
      </div>`);
    bindTip(col, format(r.value), r.tip || r.label);
    row.append(col);
    axis.append(el(`<span>${esc(r.label)}</span>`));
  });
  return node;
}

/* ── line with a crosshair ────────────────────────────────── */
export function lineChart(points, {
  xLabel = "", yLabel = "", xFormat = String, yFormat = String, tipLabel = (p) => `${xLabel} ${xFormat(p.x)}`,
  yMin = 0, yMax, marks = [], xTicks = [], label = "Line chart", height = 250,
} = {}) {
  const xs = points.map((p) => p.x);
  const xLo = Math.min(...xs);
  const xHi = Math.max(...xs);
  const ys = points.map((p) => p.y);
  const yHi = yMax ?? Math.max(...ys);
  const ticks = ticksFor(yHi - yMin).map((t) => t + yMin);
  const top = Math.max(ticks[ticks.length - 1], yHi);
  const X = (x) => ((x - xLo) / (xHi - xLo || 1)) * 100;
  const Y = (y) => ((y - yMin) / (top - yMin || 1)) * 100;

  const line = points.map((p) => `${X(p.x).toFixed(2)},${(100 - Y(p.y)).toFixed(2)}`).join(" ");
  const area = `0,100 ${line} 100,100`;
  const gridHtml = ticks.map((t) => `
    <i class="cx-grid" style="bottom:${Y(t)}%"><span>${esc(yFormat(t))}</span></i>`).join("");

  const node = el(`
    <div class="cx-line" tabindex="0" role="group" aria-label="${esc(label)}">
      ${yLabel ? `<div class="cx-axis cx-axis--y">${esc(yLabel)}</div>` : ""}
      <div class="cx-plot" style="height:${height}px">
        ${gridHtml}
        <svg viewBox="0 0 100 100" preserveAspectRatio="none" aria-hidden="true">
          <polygon points="${area}" class="cx-line__area"></polygon>
          <polyline points="${line}" class="cx-line__path" pathLength="1"></polyline>
        </svg>
        ${marks.map((m) => `
          <span class="cx-mark" style="left:${X(m.x)}%;bottom:${Y(m.y)}%">
            <i></i><b class="${m.side === "left" ? "is-left" : ""}">${esc(m.label)}</b>
          </span>`).join("")}
        <i class="cx-line__cross" hidden></i>
        <i class="cx-line__dot" hidden></i>
      </div>
      <div class="cx-line__x">
        ${xTicks.map((t) => `<span style="left:${X(t)}%">${esc(xFormat(t))}</span>`).join("")}
      </div>
      ${xLabel ? `<div class="cx-axis">${esc(xLabel)}</div>` : ""}
    </div>`);

  const plot = node.querySelector(".cx-plot");
  const cross = node.querySelector(".cx-line__cross");
  const dot = node.querySelector(".cx-line__dot");
  let at = points.length - 1;

  const focusOn = (i, clientX, clientY) => {
    at = Math.max(0, Math.min(points.length - 1, i));
    const p = points[at];
    cross.hidden = false;
    dot.hidden = false;
    cross.style.left = `${X(p.x)}%`;
    dot.style.left = `${X(p.x)}%`;
    dot.style.bottom = `${Y(p.y)}%`;
    const r = plot.getBoundingClientRect();
    showTip(clientX ?? r.left + (X(p.x) / 100) * r.width, clientY ?? r.top + r.height * (1 - Y(p.y) / 100),
      yFormat(p.y), tipLabel(p));
  };
  const clear = () => { cross.hidden = true; dot.hidden = true; hideTip(); };

  plot.addEventListener("pointermove", (e) => {
    const r = plot.getBoundingClientRect();
    const target = xLo + ((e.clientX - r.left) / r.width) * (xHi - xLo);
    let best = 0;
    points.forEach((p, i) => { if (Math.abs(p.x - target) < Math.abs(points[best].x - target)) best = i; });
    focusOn(best, e.clientX, e.clientY);
  });
  plot.addEventListener("pointerleave", clear);
  node.addEventListener("focus", () => focusOn(at));
  node.addEventListener("blur", clear);
  node.addEventListener("keydown", (e) => {
    if (e.key === "ArrowRight" || e.key === "ArrowLeft") {
      e.stopPropagation();
      e.preventDefault();
      focusOn(at + (e.key === "ArrowRight" ? 1 : -1));
    }
  });
  return node;
}

/* ── stacked bars: one whole split into parts ─────────────── */
export function stacked(rows, { max, label = "Stacked bars" } = {}) {
  const top = max ?? Math.max(...rows.map((r) => r.parts.reduce((s, p) => s + p.value, 0)), 1e-9);
  const node = el(`<div class="cx-bars" role="list" aria-label="${esc(label)}"></div>`);
  rows.forEach((r, i) => {
    const summary = r.parts.map((p) => `${p.name} ${p.text}`).join(", ");
    const row = el(`
      <div class="cx-bar" role="listitem" tabindex="0" aria-label="${esc(`${r.label}: ${summary}, total ${r.display}`)}">
        <span class="cx-bar__l">${esc(r.label)}${r.sub ? `<small>${esc(r.sub)}</small>` : ""}</span>
        <span class="cx-bar__t">
          <span class="cx-stack" style="--p:${r.parts.reduce((s, p) => s + p.value, 0) / top};animation-delay:${i * 35}ms"></span>
          <b class="cx-bar__v">${esc(r.display)}</b>
        </span>
      </div>`);
    const stack = row.querySelector(".cx-stack");
    const whole = r.parts.reduce((s, p) => s + p.value, 0) || 1;
    r.parts.forEach((p) => {
      const part = el(`<i class="cx-tone--${p.tone}" style="flex:${p.value / whole} 1 0"></i>`);
      bindTip(part, p.text, `${p.name} · ${r.label}`);
      stack.append(part);
    });
    row.addEventListener("focus", () => {
      const b = row.getBoundingClientRect();
      showTip(b.left + b.width / 2, b.top, r.display, summary);
    });
    row.addEventListener("blur", hideTip);
    node.append(row);
  });
  return node;
}

/* ── additive breakdown ───────────────────────────────────── */
export function waterfall(steps, { max, label = "Breakdown" } = {}) {
  let running = 0;
  const placed = steps.map((s) => {
    if (s.kind === "total") return { ...s, from: 0, to: s.value };
    const from = running;
    running += s.value;
    return { ...s, from, to: running };
  });
  const top = max ?? Math.max(...placed.flatMap((s) => [s.from, s.to]), 1e-9);
  const node = el(`<div class="cx-bars cx-fall" role="list" aria-label="${esc(label)}"></div>`);
  placed.forEach((s) => {
    const lo = Math.max(0, Math.min(s.from, s.to));
    const hi = Math.min(top, Math.max(s.from, s.to));
    const row = el(`
      <div class="cx-bar ${s.kind === "total" ? "is-total" : ""}" role="listitem" tabindex="0">
        <span class="cx-bar__l">${esc(s.label)}${s.sub ? `<small>${esc(s.sub)}</small>` : ""}</span>
        <span class="cx-fall__t">
          <i class="cx-tone--${s.tone || "muted"}" style="left:${(lo / top) * 100}%;
            width:max(3px,${((hi - lo) / top) * 100}%)"></i>
        </span>
        <b class="cx-bar__v">${esc(s.display)}</b>
      </div>`);
    bindTip(row, s.display, s.label);
    node.append(row);
  });
  return node;
}

/* ── unit chart: how little of the table is filled ────────── */
export function waffle(filledPct, { cols = 50, rows = 20, label = "" } = {}) {
  const total = cols * rows;
  const filled = Math.max(1, Math.round((filledPct / 100) * total));
  // A fixed shuffle: the same squares every time, scattered like the real table.
  const order = Array.from({ length: total }, (_, i) => i);
  let seed = 42;
  for (let i = total - 1; i > 0; i -= 1) {
    seed = (seed * 16807) % 2147483647;
    const j = seed % (i + 1);
    [order[i], order[j]] = [order[j], order[i]];
  }
  const on = new Set(order.slice(0, filled));
  const cells = order.map((_, i) => `<i class="${on.has(i) ? "on" : ""}"></i>`).join("");
  return el(`<div class="cx-waffle" role="img" aria-label="${esc(label)}"
    style="grid-template-columns:repeat(${cols},1fr)">${cells}</div>`);
}

/* ── frame, with its table view ───────────────────────────── */
export function dataTable(headers, rows) {
  return `<table class="cx-table">
    <thead><tr>${headers.map((h) => `<th>${esc(h)}</th>`).join("")}</tr></thead>
    <tbody>${rows.map((r) => `<tr>${r.map((c) => `<td>${esc(c)}</td>`).join("")}</tr>`).join("")}</tbody>
  </table>`;
}

export function chartCard(title, sub, chart, table) {
  const node = el(`
    <figure class="cx-card">
      <figcaption>
        <b>${esc(title)}</b>
        ${sub ? `<span>${esc(sub)}</span>` : ""}
      </figcaption>
      <div class="cx-card__body"></div>
      ${table ? `<details class="cx-details"><summary>View as table</summary>
        <div class="cx-details__scroll">${dataTable(table.headers, table.rows)}</div></details>` : ""}
    </figure>`);
  const body = node.querySelector(".cx-card__body");
  if (typeof chart === "string") body.innerHTML = chart; else body.append(chart);
  return node;
}
