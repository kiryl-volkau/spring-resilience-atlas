/* FlowDiagram: an SVG sequence diagram of a recorded flow (flow.schema.json version 2), with no library.
   render(host, flow, {compact, onStep, fold, room, stickyTop}) draws it into host and returns
   {select(stepId), overlay(scenario), clear(), column(participantId), width, folded, foldable}.
   Each column is as wide as its name, wrapped onto two lines when long, and the gap between two lifelines as wide as
   the labels of the arrows between them, so no name or label is cut short of ~45 characters.
   To fit room, the width in px it may take (the host's width by default), it first merges the tables into one
   "database" column, then wraps the names and lets labels run past the ends of their arrows, then folds each bean that
   only answers into its one caller; the arrow then names the table or bean. fold: true folds as far as that goes,
   false never, otherwise only as far as needed. Queues, systems, thread pools and beans that call on keep their column.
   stickyTop: the distance in px from the top of the viewport at which the participant heads stay while the page scrolls. */
var FlowDiagram = (function () {
  var NS = "http://www.w3.org/2000/svg";
  var KIND_LABEL = {EXTERNAL: "external", ENTRY: "entry", BEAN: "bean", STORE: "table", BROKER: "queue", EXECUTOR: "thread pool", SCHEDULER: "scheduler"};
  var REPLY = {RETURN: 1, RESPONSE: 1, EXTERNAL_REPLY: 1, MESSAGE_ACK: 1};
  var ASYNC = {ASYNC_FORK: 1, ASYNC_RUN: 1, MESSAGE_RECEIVE: 1, EVENT_DELIVER: 1};
  var SELF = {TX_BEGIN: "begins", TX_COMMIT: "commits", TX_ROLLBACK: "rolls back"};
  var STATE_GLYPH = {LOST: "⊘ lost", DUPLICATE: "×2", HUNG: "⧖ hung", BROKEN: "✕", SKIPPED: "skipped"};
  var SOURCE = {EXTERNAL: 1, BROKER: 1, SCHEDULER: 1};
  var DB = "P-database";

  function node(name, attrs, parent) {
    var e = document.createElementNS(NS, name);
    for (var k in attrs) if (attrs[k] != null) e.setAttribute(k, attrs[k]);
    if (parent) parent.appendChild(e);
    return e;
  }
  function text(parent, x, y, s, cls, anchor) {
    var t = node("text", {x: x, y: y, "class": cls, "text-anchor": anchor || "middle"}, parent);
    t.textContent = s;
    return t;
  }
  function tip(parent, s) { node("title", {}, parent).textContent = s; }

  // Text width in px, measured with the font the diagram's CSS gives it; a per-character estimate without a canvas.
  function measurer(svg) {
    var ctx = null, family = "sans-serif", cache = {};
    try {
      ctx = document.createElement("canvas").getContext("2d");
      family = getComputedStyle(svg).fontFamily || family;
    } catch (e) { ctx = null; }
    return function (s, size, bold) {
      var key = size + (bold ? "b" : "") + s;
      if (key in cache) return cache[key];
      if (!ctx) return (cache[key] = s.length * size * (bold ? 0.62 : 0.56));
      ctx.font = (bold ? "bold " : "") + size + "px " + family;
      return (cache[key] = ctx.measureText(s).width);
    };
  }

  function render(host, flow, opts) {
    opts = opts || {};
    var c = opts.compact;
    var rowH = c ? 30 : 34, segH = c ? 26 : 34, pad = c ? 10 : 18, fs = c ? 11 : 12, lineH = c ? 13 : 15;
    var headMin = c ? 72 : 96, headMax = c ? 132 : 176, gapMin = c ? 10 : 14, labelMax = c ? 220 : 300, badge = c ? 32 : 44;
    var parts = flow.participants || [], steps = flow.steps || [];
    var byId = {}, seen = {};
    parts.forEach(function (p) { byId[p.id] = p; });
    byId[DB] = {id: DB, kind: "STORE", name: "database"};
    steps.forEach(function (s) { seen[s.from] = seen[s.to] = 1; });

    if (host.dgRelease) host.dgRelease();
    host.dgRelease = null;
    host.innerHTML = "";
    var svg = node("svg", {"class": "dg" + (c ? " dg-compact" : ""), role: "img", "aria-label": "Sequence diagram: " + (flow.title || flow.id), "font-size": fs}, host);
    var measure = measurer(svg);
    function width(s, size, bold) { return measure(String(s), size || fs, bold); }
    function fit(s, px, size, bold) {
      s = String(s == null ? "" : s);
      if (width(s, size, bold) <= px) return s;
      var lo = 1, hi = s.length - 1;
      while (lo < hi) {
        var m = (lo + hi + 1) >> 1;
        if (width(s.slice(0, m) + "…", size, bold) <= px) lo = m; else hi = m - 1;
      }
      return s.slice(0, lo) + "…";
    }
    // A name wider than px breaks where a word ends: at a camel hump or after "-", "_", "." or "/", whichever leaves
    // the shorter longest line; a line is cut only past cap.
    function wrap(name, px, cap) {
      name = String(name == null ? "" : name);
      if (width(name, fs, true) <= px) return [name];
      var best = null;
      for (var i = 1; i < name.length; i++) {
        var a = name.charAt(i - 1), b = name.charAt(i);
        if (!(/[-_.\/ ]/.test(a) || /[a-z0-9]/.test(a) && /[A-Z]/.test(b))) continue;
        var w = Math.max(width(name.slice(0, i), fs, true), width(name.slice(i), fs, true));
        if (!best || w < best.w) best = {i: i, w: w};
      }
      return best ? [fit(name.slice(0, best.i).trim(), cap, fs, true), fit(name.slice(best.i).trim(), cap, fs, true)] : [fit(name, cap, fs, true)];
    }

    // squeeze (0..1), when given, wraps every long name and keeps only that share of each label's width between
    // the lifelines it joins; the rest of the label runs past the ends of its arrow.
    function plan(level, squeeze) {
      var tight = squeeze != null, keep = tight ? squeeze : 1;
      var alias = {}, folded = {};
      function fold(id, into) { alias[id] = into; (folded[into] = folded[into] || []).push(byId[id].name); }
      if (level > 0) {
        var stores = Object.keys(seen).filter(function (id) { return byId[id] && byId[id].kind === "STORE"; });
        if (stores.length > 1) stores.forEach(function (id) { fold(id, DB); });
      }
      if (level > 1) {
        var callers = {}, speaks = {};
        steps.forEach(function (s) {
          if (REPLY[s.kind]) return;
          speaks[s.from] = 1;
          if (s.to !== s.from) (callers[s.to] = callers[s.to] || {})[s.from] = 1;
        });
        Object.keys(callers).forEach(function (id) {
          var from = Object.keys(callers[id]);
          if (byId[id] && byId[id].kind === "BEAN" && !speaks[id] && from.length === 1 && byId[from[0]]) fold(id, from[0]);
        });
      }
      function at(id) { return alias[id] || id; }
      var order = [], first = {}, source = {}, segSeen = {};
      function use(id) { id = at(id); if (byId[id] && !(id in first)) { first[id] = order.length; order.push(id); } }
      steps.forEach(function (s) { use(s.from); use(s.to); });
      parts.forEach(function (p) { if (!alias[p.id]) use(p.id); });
      steps.forEach(function (s) {
        if (segSeen[s.segment]) return;
        segSeen[s.segment] = 1;
        if (byId[s.from] && SOURCE[byId[s.from].kind]) source[at(s.from)] = 1;
      });
      // Callers before callees: what starts a segment, then entry points, beans and pools, then tables, then the queues and systems the flow sends to.
      function layer(id) { var k = byId[id].kind; return source[id] ? 0 : k === "STORE" ? 2 : k === "EXTERNAL" || k === "BROKER" ? 3 : 1; }
      order.sort(function (a, b) { return layer(a) - layer(b) || first[a] - first[b]; });
      function labelOf(s) {
        var other = alias[s.to] ? s.to : alias[s.from] ? s.from : null;
        return (other && s.from !== s.to && byId[other] ? byId[other].name + " · " : "") + (s.label || s.kind);
      }

      var col = {};
      var heads = order.map(function (id, i) {
        var p = byId[id], more = folded[id] ? folded[id].length : 0;
        var kind = c ? "" : id === DB ? more + " tables" : (KIND_LABEL[p.kind] || p.kind) + (more ? " +" + more + (more === 1 ? " bean" : " beans") : "");
        var lines = wrap(p.name, (tight ? headMin : headMax) - 18, headMax - 18);
        var w = Math.max.apply(null, lines.map(function (l) { return width(l, fs, true); }).concat([width(kind.toUpperCase(), 10) + kind.length * 0.5]));
        col[id] = i;
        return {id: id, kind: kind, lines: lines, w: Math.min(headMax, Math.max(headMin, w + 18)), more: more};
      });
      var n = heads.length, gap = [];
      for (var i = 0; i + 1 < n; i++) gap.push((heads[i].w + heads[i + 1].w) / 2 + gapMin);
      gap.push(n ? heads[n - 1].w / 2 : 0);
      // Each label asks for room between two lifelines; the narrow asks go first, and a wide one shares out what it still lacks.
      // A loop's label sits alone on its row and may run across the next lifelines, so it only asks for room past the last one.
      var asks = [];
      steps.forEach(function (s) {
        var a = col[at(s.from)], b = col[at(s.to)];
        if (a == null || b == null) return;
        if (SELF[s.kind]) asks.push({lo: a, hi: a + 1, need: 10 + width((s.tx || "") + " " + SELF[s.kind]) + 12});
        else if (a !== b) asks.push({lo: Math.min(a, b), hi: Math.max(a, b), need: keep * Math.min(labelMax, width(labelOf(s))) + badge});
        else if (a === n - 1) asks.push({lo: a, hi: n, need: 28 + Math.min(labelMax, width(labelOf(s))) + 12});
      });
      asks.sort(function (p, q) { return (p.hi - p.lo) - (q.hi - q.lo); });
      asks.forEach(function (k) {
        var have = 0;
        for (var j = k.lo; j < k.hi; j++) have += gap[j];
        if (have < k.need) for (j = k.lo; j < k.hi; j++) gap[j] += (k.need - have) / (k.hi - k.lo);
      });
      var x = [];
      heads.forEach(function (h, i) { x.push(i ? x[i - 1] + gap[i - 1] : pad + h.w / 2); });
      return {level: level, alias: alias, folded: folded, order: order, heads: heads, x: x, at: at, labelOf: labelOf,
        width: n ? x[n - 1] + gap[n - 1] + pad : pad * 2, lines: Math.max.apply(null, [1].concat(heads.map(function (h) { return h.lines.length; })))};
    }

    var room = opts.room || host.clientWidth || Infinity;
    function squeezed(level) {
      var lo = 0, hi = 1;
      if (plan(level, 0).width > room) return plan(level, 0);
      for (var i = 0; i < 7; i++) {
        var m = (lo + hi) / 2;
        if (plan(level, m).width <= room) lo = m; else hi = m;
      }
      return plan(level, lo);
    }
    // The first that fits, else the narrowest: whole, tables merged, then squeezed, then beans folded.
    var tries = opts.fold === false ? [plan(0), squeezed(0)] : opts.fold === true ? [plan(2), squeezed(2)]
      : [plan(0), plan(1), squeezed(1), plan(2), squeezed(2)];
    var P = tries.filter(function (p) { return p.width <= room; })[0] || tries[tries.length - 1];
    var foldable = P.level === 0 && P.width > room && plan(2).order.length < P.order.length;
    var at = P.at, alias = P.alias, col = {};
    P.order.forEach(function (id, i) { col[id] = P.x[i]; });
    var headH = (c ? 40 : 54) + (P.lines - 1) * lineH;
    var entryBySeg = {};
    (flow.entries || []).forEach(function (e) { entryBySeg[e.segment] = e; });
    var linkInto = {};
    (flow.links || []).forEach(function (l) { if (l.toStep) (linkInto[l.toStep] = linkInto[l.toStep] || []).push(l); });

    var rows = [], y = headH + 8, seg = null, started = {};
    steps.forEach(function (s) {
      if (s.segment !== seg) {
        seg = s.segment;
        rows.push({seg: seg, y: y + segH / 2, links: linkInto[s.id] || [], again: !!started[seg]});
        started[seg] = true;
        y += segH;
      }
      rows.push({step: s, y: y + rowH / 2});
      y += rowH;
    });
    var W = Math.ceil(P.width), height = y + pad;
    var rowOf = {};
    rows.forEach(function (r) { if (r.step) rowOf[r.step.id] = r; });
    svg.setAttribute("width", W);
    svg.setAttribute("height", height);
    svg.setAttribute("viewBox", "0 0 " + W + " " + height);
    var defs = node("defs", {}, svg);
    var marker = node("marker", {id: "dg-arrow" + (c ? "-c" : ""), viewBox: "0 0 10 10", refX: 9, refY: 5, markerWidth: 7, markerHeight: 7, orient: "auto-start-reverse"}, defs);
    node("path", {d: "M0,0 L10,5 L0,10 z", "class": "dg-head"}, marker);

    rows.forEach(function (r) {
      if (!r.seg) return;
      var e = entryBySeg[r.seg] || {};
      node("rect", {x: 2, y: r.y - segH / 2 + 3, width: W - 4, height: segH - 6, rx: 6, "class": "dg-seg"}, svg);
      var link = r.links.length ? r.links[0] : null;
      var label = r.again ? r.seg + " · continues after the work above" : r.seg + " · " + [e.kind, e.subkind, e.target].filter(Boolean).join(" ") + (e.trigger ? " · started by " + e.trigger : "") +
        (link ? "  ·  via " + (link.key || link.kind) + (link.decision && link.decision.accepted === false ? " (rejected)" : link.confidence ? " (" + String(link.confidence).toLowerCase() + ")" : "") : "");
      var t = text(svg, 12, r.y + fs / 3, fit(label, W - 24, fs, true), "dg-seg-t", "start");
      tip(t, label);
    });

    P.order.forEach(function (id) {
      node("line", {x1: col[id], y1: headH, x2: col[id], y2: height - pad / 2, "class": "dg-life dg-life-" + byId[id].kind}, svg);
    });

    (flow.transactions || []).forEach(function (t) {
      var b = rowOf[t.begin], e = rowOf[t.commit];
      if (!b || !e) return;
      var x = col[at(b.step.from)];
      var band = node("rect", {x: x - 5, y: b.y - 6, width: 10, height: Math.max(12, e.y - b.y + 12), rx: 3, "class": "dg-tx"}, svg);
      tip(band, t.id + " · " + t.owner + (t.readOnly ? " (read-only)" : ""));
    });

    var groups = {};
    rows.forEach(function (r) {
      if (!r.step) return;
      var s = r.step, x1 = col[at(s.from)], x2 = col[at(s.to)], yy = r.y;
      if (x1 == null || x2 == null) return;
      var g = node("g", {"class": "dg-s dg-k-" + s.kind + (REPLY[s.kind] ? " dg-reply" : ASYNC[s.kind] || s.sync === false ? " dg-async" : ""),
        "data-step": s.id, tabindex: 0, role: "button"}, svg);
      groups[s.id] = g;
      var label = P.labelOf(s);
      if (!SELF[s.kind] && x1 === x2) {
        node("path", {d: "M" + x1 + "," + (yy - 6) + " h22 v12 h-19", "class": "dg-arrow", fill: "none", "marker-end": "url(#dg-arrow" + (c ? "-c" : "") + ")"}, g);
        text(g, x1 + 28, yy + fs / 3, fit(label, Math.max(labelMax, W - x1 - 28 - pad)), "dg-l", "start");
      } else if (SELF[s.kind]) {
        node("circle", {cx: x1, cy: yy, r: 4.5, "class": "dg-self"}, g);
        text(g, x1 + 10, yy + fs / 3, (s.tx || "") + " " + SELF[s.kind], "dg-l", "start");
      } else {
        var dir = x2 > x1 ? 1 : -1;
        node("line", {x1: x1, y1: yy, x2: x2 - dir * 2, y2: yy, "class": "dg-arrow", "marker-end": "url(#dg-arrow" + (c ? "-c" : "") + ")"}, g);
        node("line", {x1: x1, y1: yy, x2: x2, y2: yy, "class": "dg-hit"}, g);
        // A label wider than its arrow starts at the caller and runs on past the arrow's end, clear of the caller's transaction band.
        var shown = fit(label, W - 8), lw = width(shown), ly = yy - (c ? 8 : 11);
        if (lw + 16 <= Math.abs(x2 - x1)) text(g, (x1 + x2) / 2, ly, shown, "dg-l");
        else if (dir > 0) text(g, Math.min(x1 + 8, W - 4 - lw), ly, shown, "dg-l", "start");
        else text(g, Math.max(x1 - 8, lw + 4), ly, shown, "dg-l", "end");
        if (s.durable === false) node("circle", {cx: x2 - dir * 20, cy: yy, r: 3.2, "class": "dg-volatile"}, g);
      }
      var nx = x1 === x2 ? x1 - 16 : x1 + (x2 > x1 ? 13 : -13);
      node("circle", {cx: nx, cy: yy, r: c ? 7 : 8.5, "class": "dg-n"}, g);
      text(g, nx, yy + (c ? 3.5 : 4), String(s.n), "dg-n-t");
      tip(g, s.n + ". " + label + (s.notes ? " — " + s.notes : "") + (s.durable === false ? " (not durable)" : ""));
      function pick() { api.select(s.id); if (opts.onStep) opts.onStep(s); }
      g.addEventListener("click", pick);
      g.addEventListener("keydown", function (ev) { if (ev.key === "Enter" || ev.key === " ") { ev.preventDefault(); pick(); } });
    });

    var overlayLayer = node("g", {"class": "dg-overlay"}, svg);

    // Drawn last so that, held at the top while the page scrolls, the heads cover the rows under them.
    var top = node("g", {"class": "dg-heads"}, svg);
    node("rect", {x: 0, y: 0, width: W, height: headH, "class": "dg-heads-bg"}, top);
    node("line", {x1: 0, y1: headH - 0.5, x2: W, y2: headH - 0.5, "class": "dg-heads-rule"}, top);
    P.heads.forEach(function (h) {
      var p = byId[h.id], x = col[h.id];
      var g = node("g", {"class": "dg-p dg-p-" + p.kind, "data-p": h.id}, top);
      node("rect", {x: x - h.w / 2, y: 6, width: h.w, height: headH - 12, rx: 8}, g);
      if (!c) text(g, x, 21, h.kind, "dg-p-k");
      var y0 = (c ? (headH - (P.lines - 1) * lineH) / 2 + 4 : 37) + (P.lines - h.lines.length) * lineH / 2;
      h.lines.forEach(function (l, k) { text(g, x, y0 + k * lineH, l, "dg-p-n"); });
      tip(g, p.name + (p.detail ? " — " + p.detail : "") + " (" + (KIND_LABEL[p.kind] || p.kind) + ")" + (h.more ? "; folded in: " + P.folded[h.id].join(", ") : ""));
    });
    if (opts.stickyTop != null && window.requestAnimationFrame) {
      var frame = 0;
      var stick = function () {
        frame = 0;
        var dy = Math.max(0, Math.min(opts.stickyTop - svg.getBoundingClientRect().top, height - headH - rowH));
        top.setAttribute("transform", "translate(0," + dy + ")");
        top.classList.toggle("dg-stuck", dy > 0);
      };
      var onScroll = function () { if (!frame) frame = requestAnimationFrame(stick); };
      window.addEventListener("scroll", onScroll, {passive: true});
      window.addEventListener("resize", onScroll);
      host.dgRelease = function () {
        window.removeEventListener("scroll", onScroll);
        window.removeEventListener("resize", onScroll);
        if (frame) cancelAnimationFrame(frame);
      };
      stick();
    }

    var api = {
      svg: svg,
      width: W,
      folded: P.level > 0,
      foldable: foldable,
      column: at,
      select: function (id) {
        Object.keys(groups).forEach(function (k) { groups[k].classList.toggle("dg-sel", k === id); });
      },
      clear: function () {
        svg.classList.remove("dg-dim");
        overlayLayer.innerHTML = "";
        Object.keys(groups).forEach(function (k) {
          groups[k].classList.remove("dg-on", "dg-broken");
          groups[k].removeAttribute("data-state");
        });
      },
      overlay: function (sc) {
        api.clear();
        if (!sc) return;
        svg.classList.add("dg-dim");
        var minY = null, maxY = null;
        (sc.steps || []).forEach(function (st) {
          var g = st.stepRef && groups[st.stepRef], r = st.stepRef && rowOf[st.stepRef];
          if (!g || !r) return;
          g.classList.add("dg-on");
          if (st.state && st.state !== "OK") g.setAttribute("data-state", st.state);
          minY = minY == null ? r.y : Math.min(minY, r.y);
          maxY = maxY == null ? r.y : Math.max(maxY, r.y);
          var s = r.step, xf = col[at(s.from)], xt = col[at(s.to)];
          var x = SELF[s.kind] ? xf + 92 : xf === xt ? xf - 46 : Math.max(xf, xt) + 10;
          var b = node("g", {"class": "dg-sc dg-sc-" + (st.state || "OK")}, overlayLayer);
          node("rect", {x: x, y: r.y - 9, width: 18, height: 18, rx: 9}, b);
          text(b, x + 9, r.y + 4, String(st.n), "dg-sc-t");
          if (STATE_GLYPH[st.state]) text(b, x + 24, r.y + 4, STATE_GLYPH[st.state], "dg-sc-g", "start");
          tip(b, st.n + ". " + st.happens);
        });
        var broken = sc.brokenAt && groups[sc.brokenAt];
        if (broken) broken.classList.add("dg-broken");
        if (sc.concept && sc.concept.scope === "CONCEPT" && minY != null) {
          var bx = W - 10;
          node("path", {d: "M" + (bx - 6) + "," + (minY - 10) + " h6 v" + (maxY - minY + 20) + " h-6", "class": "dg-bracket"}, overlayLayer);
          var lt = text(overlayLayer, bx - 10, minY - 14, "design-level", "dg-bracket-t", "end");
          tip(lt, sc.because || "");
        }
      }
    };
    return api;
  }

  return {render: render};
})();
