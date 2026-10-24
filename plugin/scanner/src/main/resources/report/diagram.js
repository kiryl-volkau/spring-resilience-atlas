/* FlowDiagram: an SVG sequence diagram of a recorded flow (flow.schema.json version 2), with no library.
   render(host, flow, {compact, onStep, fold}) draws it into host and returns {select(stepId), overlay(scenario), clear(), folded, foldable}.
   fold: true folds each segment's beans into its entry point's column, false never, otherwise only when the
   participants would not fit (more than FOLD_AT columns). Tables, queues, systems and pools always keep a column. */
var FlowDiagram = (function () {
  var NS = "http://www.w3.org/2000/svg";
  var KIND_LABEL = {EXTERNAL: "external", ENTRY: "entry", BEAN: "bean", STORE: "table", BROKER: "queue", EXECUTOR: "thread pool", SCHEDULER: "scheduler"};
  var REPLY = {RETURN: 1, RESPONSE: 1, EXTERNAL_REPLY: 1, MESSAGE_ACK: 1};
  var ASYNC = {ASYNC_FORK: 1, ASYNC_RUN: 1, MESSAGE_RECEIVE: 1, EVENT_DELIVER: 1};
  var SELF = {TX_BEGIN: "begins", TX_COMMIT: "commits", TX_ROLLBACK: "rolls back"};
  var STATE_GLYPH = {LOST: "⊘ lost", DUPLICATE: "×2", HUNG: "⧖ hung", BROKEN: "✕", SKIPPED: "skipped"};
  var FOLD_AT = 12, FOLD_AT_COMPACT = 9, MERGE_AT = 10;
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
  function cut(s, n) { s = String(s == null ? "" : s); return s.length > n ? s.slice(0, Math.max(1, n - 1)) + "…" : s; }
  function tip(parent, s) { node("title", {}, parent).textContent = s; }

  function render(host, flow, opts) {
    opts = opts || {};
    var c = opts.compact;
    var colW = c ? 96 : 124, headH = c ? 40 : 54, rowH = c ? 24 : 34, segH = c ? 26 : 34, pad = c ? 10 : 18, fs = c ? 11 : 12;
    var charW = fs * 0.56;
    var parts = flow.participants || [], steps = flow.steps || [];
    var byId = {}, order = [], seen = {};
    parts.forEach(function (p) { byId[p.id] = p; });
    steps.forEach(function (s) { seen[s.from] = seen[s.to] = 1; });
    var foldable = Object.keys(seen).filter(function (id) { return byId[id]; }).length > (c ? FOLD_AT_COMPACT : FOLD_AT);
    var fold = opts.fold === true || (opts.fold !== false && foldable);
    var alias = {}, folded = {};
    if (fold) {
      var entryOfSeg = {};
      steps.forEach(function (s) {
        [s.from, s.to].forEach(function (id) { if (byId[id] && byId[id].kind === "ENTRY" && !entryOfSeg[s.segment]) entryOfSeg[s.segment] = id; });
      });
      steps.forEach(function (s) {
        [s.from, s.to].forEach(function (id) {
          var into = entryOfSeg[s.segment];
          if (byId[id] && byId[id].kind === "BEAN" && !alias[id] && into) {
            alias[id] = into;
            (folded[into] = folded[into] || []).push(byId[id].name);
          }
        });
      });
    }
    if (fold) {
      var columns = {};
      steps.forEach(function (s) { [s.from, s.to].forEach(function (id) { if (byId[id]) columns[alias[id] || id] = 1; }); });
      var stores = Object.keys(columns).filter(function (id) { return byId[id].kind === "STORE"; });
      if (Object.keys(columns).length > (c ? FOLD_AT_COMPACT : MERGE_AT) && stores.length > 1) {
        byId[DB] = {id: DB, kind: "STORE", name: "database"};
        stores.forEach(function (id) { alias[id] = DB; (folded[DB] = folded[DB] || []).push(byId[id].name); });
      }
    }
    function at(id) { return alias[id] || id; }
    function use(id) { id = at(id); if (byId[id] && order.indexOf(id) < 0) order.push(id); }
    steps.forEach(function (s) { use(s.from); use(s.to); });
    parts.forEach(function (p) { if (!alias[p.id]) use(p.id); });
    var col = {};
    order.forEach(function (id, i) { col[id] = pad + i * colW + colW / 2; });
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
    var width = pad * 2 + order.length * colW, height = y + pad;
    var rowOf = {};
    rows.forEach(function (r) { if (r.step) rowOf[r.step.id] = r; });

    host.innerHTML = "";
    var svg = node("svg", {width: width, height: height, viewBox: "0 0 " + width + " " + height, "class": "dg" + (c ? " dg-compact" : ""),
      role: "img", "aria-label": "Sequence diagram: " + (flow.title || flow.id), "font-size": fs}, host);
    var defs = node("defs", {}, svg);
    var marker = node("marker", {id: "dg-arrow" + (c ? "-c" : ""), viewBox: "0 0 10 10", refX: 9, refY: 5, markerWidth: 7, markerHeight: 7, orient: "auto-start-reverse"}, defs);
    node("path", {d: "M0,0 L10,5 L0,10 z", "class": "dg-head"}, marker);

    rows.forEach(function (r) {
      if (!r.seg) return;
      var e = entryBySeg[r.seg] || {};
      node("rect", {x: 2, y: r.y - segH / 2 + 3, width: width - 4, height: segH - 6, rx: 6, "class": "dg-seg"}, svg);
      var link = r.links.length ? r.links[0] : null;
      var label = r.again ? r.seg + " · continues after the work above" : r.seg + " · " + [e.kind, e.subkind, e.target].filter(Boolean).join(" ") + (e.trigger ? " · started by " + e.trigger : "") +
        (link ? "  ·  via " + (link.key || link.kind) + (link.decision && link.decision.accepted === false ? " (rejected)" : link.confidence ? " (" + String(link.confidence).toLowerCase() + ")" : "") : "");
      var t = text(svg, 12, r.y + fs / 3, cut(label, Math.floor((width - 24) / charW)), "dg-seg-t", "start");
      tip(t, label);
    });

    order.forEach(function (id) {
      var p = byId[id], x = col[id];
      node("line", {x1: x, y1: headH, x2: x, y2: height - pad / 2, "class": "dg-life dg-life-" + p.kind}, svg);
      var g = node("g", {"class": "dg-p dg-p-" + p.kind, "data-p": id}, svg);
      node("rect", {x: x - colW / 2 + 5, y: 6, width: colW - 10, height: headH - 12, rx: 8}, g);
      var more = folded[id] ? folded[id].length : 0;
      if (!c) text(g, x, 21, id === DB ? more + " tables" : (KIND_LABEL[p.kind] || p.kind) + (more ? " +" + more + (more === 1 ? " bean" : " beans") : ""), "dg-p-k");
      text(g, x, c ? headH / 2 + 4 : 37, cut(p.name, Math.floor((colW - 16) / charW)), "dg-p-n");
      tip(g, p.name + (p.detail ? " — " + p.detail : "") + " (" + (KIND_LABEL[p.kind] || p.kind) + ")" + (more ? "; folded in: " + folded[id].join(", ") : ""));
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
      var label = s.label || s.kind;
      var other = alias[s.to] ? s.to : alias[s.from] === DB ? s.from : null;
      if (other && s.from !== s.to && byId[other]) label = byId[other].name + " · " + label;
      if (!SELF[s.kind] && x1 === x2) {
        node("path", {d: "M" + x1 + "," + (yy - 6) + " h22 v12 h-19", "class": "dg-arrow", fill: "none", "marker-end": "url(#dg-arrow" + (c ? "-c" : "") + ")"}, g);
        text(g, x1 + 28, yy + fs / 3, cut(label, Math.floor(colW * 1.3 / charW)), "dg-l", "start");
      } else if (SELF[s.kind]) {
        node("circle", {cx: x1, cy: yy, r: 4.5, "class": "dg-self"}, g);
        text(g, x1 + 10, yy + fs / 3, cut((s.tx || "") + " " + SELF[s.kind], 18), "dg-l", "start");
      } else {
        var dir = x2 > x1 ? 1 : -1;
        node("line", {x1: x1, y1: yy, x2: x2 - dir * 2, y2: yy, "class": "dg-arrow", "marker-end": "url(#dg-arrow" + (c ? "-c" : "") + ")"}, g);
        node("line", {x1: x1, y1: yy, x2: x2, y2: yy, "class": "dg-hit"}, g);
        var span = Math.abs(x2 - x1);
        text(g, (x1 + x2) / 2, yy - 11, cut(label, Math.max(Math.floor(colW * 1.5 / charW), Math.floor((span - 20) / charW))), "dg-l");
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
    var api = {
      svg: svg,
      folded: fold,
      foldable: foldable,
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
          var bx = width - 10;
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
