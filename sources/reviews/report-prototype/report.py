"""Render the resilience audit report: one self-contained HTML file from JSON.

Spec for a future `report` command in resilience-atlas.jar. No LLM is involved: every
colour is derived here, deterministically, from the verdict and finding records.

Usage:
    py -I report.py --atlas <atlas-dir> --operations operations.jsonl --verdicts verdicts.jsonl
                    --findings findings.jsonl --coverage coverage.json --flows <flows-dir>
                    --out report.html [--banner "PROTOTYPE - sample verdicts"]

Inputs (all optional except the atlas and operations; a missing file means "nothing ran"):
    manifest.json        atlas commit, warnings, counts            (jar `scan`)
    operations.jsonl     one record per entry point, static signals, preliminary hint, score  (jar `classify`)
    verdicts.jsonl       one record per analysed operation: tier, findings, flow, run       (LLM tiers)
    findings.jsonl       evidence packets, finding.schema.json plus `operationId`, `title`  (LLM tiers)
    coverage.json        ledger: runs, budget, plan, pending, limits                        (orchestrator)
    flows/<id>.json      flow.schema.json, used for the flow sketch                         (flow-tracer)

Colour rules (the contract the page explains under "Method"):
    GREY    no verdict, or a STALE verdict. Never derived from the static hint alone.
    RED     DEEP/VERIFIED tier and a live finding of severity CRITICAL/HIGH that is VERIFIED,
            or CANDIDATE with HIGH confidence.
    YELLOW  DEEP/VERIFIED tier with only lower-severity or lower-confidence live findings;
            or TRIAGE verdict YELLOW; or TRIAGE verdict RED (triage cannot assert RED, it escalates).
    GREEN   DEEP/VERIFIED tier with no live finding; or TRIAGE verdict GREEN.
"""

import argparse
import datetime as dt
import json
from pathlib import Path

SEVERITY_RANK = {"CRITICAL": 4, "HIGH": 3, "MEDIUM": 2, "LOW": 1}
CONFIDENCE_RANK = {"HIGH": 3, "MEDIUM": 2, "LOW": 1}
LABELS = {"RED": "Broken", "YELLOW": "Smells", "GREEN": "OK", "GREY": "Not analysed"}


def load_jsonl(path):
    if not path or not Path(path).exists():
        return []
    with open(path, encoding="utf-8") as f:
        return [json.loads(line) for line in f if line.strip()]


def load_json(path, default):
    if not path or not Path(path).exists():
        return default
    return json.loads(Path(path).read_text(encoding="utf-8"))


def live(findings):
    return [f for f in findings if f.get("status") not in ("DISPROVED", "DUPLICATE")]


def resolve_colour(verdict, findings):
    """Returns (colour, confidence, note)."""
    if verdict is None:
        return "GREY", None, "not analysed"
    if verdict.get("status") == "STALE":
        return "GREY", None, f"stale: files changed since the verdict (was {verdict.get('previousColour', '?')})"
    tier = verdict["tier"]
    if tier in ("DEEP", "VERIFIED"):
        open_findings = live(findings)
        red = [f for f in open_findings if SEVERITY_RANK[f["severity"]] >= 3 and (
            f["status"] == "VERIFIED" or f.get("confidence") == "HIGH")]
        if red:
            unverified = all(f["status"] != "VERIFIED" for f in red)
            return "RED", verdict.get("confidence", "MEDIUM"), "unverified" if unverified else ""
        if open_findings:
            return "YELLOW", verdict.get("confidence", "MEDIUM"), ""
        return "GREEN", verdict.get("confidence", "MEDIUM"), ""
    colour = verdict.get("colour", "YELLOW")
    confidence = verdict.get("confidence", "LOW")
    if CONFIDENCE_RANK.get(confidence, 1) > 2:
        confidence = "MEDIUM"
    if colour == "RED":
        return "YELLOW", confidence, "triage suspects RED; deep audit pending"
    return colour, confidence, ""


def top_issue(colour, verdict, findings, op):
    open_findings = sorted(live(findings), key=lambda f: (-SEVERITY_RANK[f["severity"]], -CONFIDENCE_RANK.get(f.get("confidence", "LOW"), 1)))
    if open_findings:
        f = open_findings[0]
        return f"{f['ruleId']} {f.get('title') or f['failureScenario'][:90]}"
    if verdict:
        return verdict.get("summary", "")
    hint = op["preliminary"]["hint"]
    if hint == "UNRATED" or not op["preliminary"]["reasons"]:
        return ""
    return "static hint: " + "; ".join(op["preliminary"]["reasons"])


def build_rows(ops, verdicts_by_id, findings_by_op):
    rows = []
    for op in ops:
        verdict = verdicts_by_id.get(op["id"])
        findings = findings_by_op.get(op["id"], [])
        colour, confidence, note = resolve_colour(verdict, findings)
        tier = verdict["tier"] if verdict and verdict.get("status") != "STALE" else "STATIC"
        rows.append({
            "id": op["id"],
            "colour": colour,
            "label": LABELS[colour],
            "note": note,
            "confidence": confidence,
            "tier": tier,
            "escalated": bool(verdict and verdict.get("escalate")),
            "domain": op.get("domain") or "(unassigned)",
            "kind": op["kind"],
            "subkind": op["subkind"],
            "target": op["target"],
            "symbol": op["symbol"],
            "file": op["file"],
            "line": op["line"],
            "discovery": op["discovery"],
            "placeholder": op.get("placeholder", False),
            "score": op["preliminary"]["score"],
            "hint": op["preliminary"]["hint"],
            "hintConfidence": op["preliminary"]["confidence"],
            "hintReasons": op["preliminary"]["reasons"],
            "signals": sorted(k for k, v in op["signals"].items() if v is True),
            "signalScope": op["signals"].get("scope", "file"),
            "reachedFiles": op["signals"].get("reachedFiles", []),
            "topIssue": top_issue(colour, verdict, findings, op),
            "verdict": verdict,
            "findings": sorted(findings, key=lambda f: -SEVERITY_RANK[f["severity"]]),
        })
    return rows


def summarise(rows, coverage, manifest):
    by_colour = {c: 0 for c in LABELS}
    by_domain, by_kind, by_tier = {}, {}, {}
    for r in rows:
        by_colour[r["colour"]] += 1
        by_domain.setdefault(r["domain"], {c: 0 for c in LABELS})[r["colour"]] += 1
        by_kind.setdefault(r["kind"], {c: 0 for c in LABELS})[r["colour"]] += 1
        by_tier[r["tier"]] = by_tier.get(r["tier"], 0) + 1
    total = len(rows)
    analysed = total - by_colour["GREY"]
    return {
        "total": total,
        "analysed": analysed,
        "coveragePct": round(100.0 * analysed / total, 1) if total else 0.0,
        "byColour": by_colour,
        "byDomain": dict(sorted(by_domain.items())),
        "byKind": dict(sorted(by_kind.items())),
        "byTier": by_tier,
        "findings": {
            "verified": sum(1 for r in rows for f in r["findings"] if f["status"] == "VERIFIED"),
            "candidate": sum(1 for r in rows for f in r["findings"] if f["status"] == "CANDIDATE"),
            "disproved": sum(1 for r in rows for f in r["findings"] if f["status"] == "DISPROVED"),
        },
        "escalatedPending": sum(1 for r in rows if r["escalated"] and r["tier"] == "TRIAGE"),
        "usdSpent": coverage.get("budget", {}).get("usdSpent"),
        "usdCap": coverage.get("budget", {}).get("usdCap"),
        "status": coverage.get("status", "NOT_RUN"),
        "warnings": manifest.get("warnings", []),
    }


def flow_sketch(flow):
    if not flow:
        return None
    return {
        "id": flow["id"],
        "title": flow.get("title"),
        "entry": flow["entrypoint"],
        "hops": [{"order": h["order"], "symbol": h["symbol"], "tx": h.get("tx"), "thread": h.get("thread"),
                  "file": h["evidence"]["file"], "line": h["evidence"]["line"], "notes": h.get("notes", "")}
                 for h in flow.get("hops", [])],
        "boundaries": [{"order": b["order"], "type": b["type"], "symbol": b["symbol"], "target": b.get("target"),
                        "tx": b.get("tx"), "durable": b.get("durable"), "file": b["evidence"]["file"],
                        "line": b["evidence"]["line"], "notes": b.get("notes", "")}
                       for b in flow.get("boundaries", [])],
        "acknowledgement": flow.get("acknowledgement"),
        "openQuestions": flow.get("openQuestions", []),
        "sourceBudget": flow.get("sourceBudget"),
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--atlas", required=True)
    ap.add_argument("--operations", required=True)
    ap.add_argument("--verdicts")
    ap.add_argument("--findings")
    ap.add_argument("--coverage")
    ap.add_argument("--flows")
    ap.add_argument("--out", required=True)
    ap.add_argument("--title", default="Resilience audit")
    ap.add_argument("--banner", default="")
    args = ap.parse_args()

    manifest = load_json(Path(args.atlas) / "manifest.json", {})
    ops = load_jsonl(args.operations)
    verdicts_by_id = {v["id"]: v for v in load_jsonl(args.verdicts)}
    findings_by_op = {}
    for f in load_jsonl(args.findings):
        findings_by_op.setdefault(f.get("operationId"), []).append(f)
    coverage = load_json(args.coverage, {})

    rows = build_rows(ops, verdicts_by_id, findings_by_op)
    flows = {}
    for r in rows:
        flow_id = (r["verdict"] or {}).get("flowId")
        if flow_id and flow_id not in flows:
            path = Path(args.flows or ".") / f"{flow_id}.json"
            flows[flow_id] = flow_sketch(load_json(path, None))

    data = {
        "title": args.title,
        "banner": args.banner,
        "generatedAt": dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "manifest": {k: manifest.get(k) for k in ("commit", "dirty", "scannedAt", "scanner", "build", "files", "warnings")},
        "summary": summarise(rows, coverage, manifest),
        "coverage": coverage,
        "rows": rows,
        "flows": flows,
    }
    payload = json.dumps(data, ensure_ascii=False, separators=(",", ":")).replace("</", "<\\/")
    html = TEMPLATE.replace("__TITLE__", args.title).replace("__DATA__", payload)
    Path(args.out).write_text(html, encoding="utf-8")
    s = data["summary"]
    print(f"# report written to {args.out}: {s['total']} operations, "
          f"RED {s['byColour']['RED']} YELLOW {s['byColour']['YELLOW']} GREEN {s['byColour']['GREEN']} "
          f"GREY {s['byColour']['GREY']}, coverage {s['coveragePct']}%")


TEMPLATE = r"""<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>__TITLE__</title>
<style>
:root{
  --bg:#f7f7f5;--panel:#ffffff;--ink:#1b1b1b;--muted:#5f6368;--line:#d9d9d4;--accent:#2b5fa3;
  --red:#b3261e;--red-bg:#fbe9e7;--yellow:#8a5a00;--yellow-bg:#fff3cd;--green:#1e6b3a;--green-bg:#e3f3e8;--grey:#5f6368;--grey-bg:#ebebe9;
  --code-bg:#f0f0ee;
}
@media (prefers-color-scheme: dark){
  :root:not([data-theme="light"]){
    --bg:#141516;--panel:#1d1f21;--ink:#e8e8e6;--muted:#9a9ea3;--line:#34373b;--accent:#8ab4f8;
    --red:#f28b82;--red-bg:#3a1f1d;--yellow:#fdd663;--yellow-bg:#3a2f12;--green:#81c995;--green-bg:#1b3324;--grey:#9a9ea3;--grey-bg:#2a2c2f;
    --code-bg:#26282b;
  }
}
:root[data-theme="dark"]{
  --bg:#141516;--panel:#1d1f21;--ink:#e8e8e6;--muted:#9a9ea3;--line:#34373b;--accent:#8ab4f8;
  --red:#f28b82;--red-bg:#3a1f1d;--yellow:#fdd663;--yellow-bg:#3a2f12;--green:#81c995;--green-bg:#1b3324;--grey:#9a9ea3;--grey-bg:#2a2c2f;
  --code-bg:#26282b;
}
*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:var(--ink);font:14px/1.45 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif}
main{max-width:1400px;margin:0 auto;padding:16px}
h1{font-size:22px;margin:0 0 4px}
h2{font-size:17px;margin:28px 0 10px;padding-top:12px;border-top:1px solid var(--line)}
h3{font-size:14px;margin:14px 0 6px}
p{margin:6px 0}
a{color:var(--accent)}
code,pre{font:12px/1.4 ui-monospace,SFMono-Regular,Menlo,Consolas,monospace}
code{background:var(--code-bg);padding:1px 4px;border-radius:3px}
pre{background:var(--code-bg);padding:8px;border-radius:4px;overflow:auto;margin:4px 0}
.banner{background:var(--yellow-bg);color:var(--yellow);border:1px solid var(--yellow);padding:8px 12px;border-radius:4px;font-weight:600;margin:8px 0 12px}
.meta{color:var(--muted);font-size:13px}
.tiles{display:grid;grid-template-columns:repeat(auto-fit,minmax(150px,1fr));gap:10px;margin:12px 0}
.tile{background:var(--panel);border:1px solid var(--line);border-radius:6px;padding:10px 12px}
.tile .n{font-size:26px;font-weight:700;line-height:1.1}
.tile .l{color:var(--muted);font-size:12px;margin-top:2px}
.tile.RED .n{color:var(--red)} .tile.YELLOW .n{color:var(--yellow)} .tile.GREEN .n{color:var(--green)} .tile.GREY .n{color:var(--grey)}
.badge{display:inline-block;padding:1px 7px;border-radius:10px;font-size:12px;font-weight:600;white-space:nowrap;border:1px solid transparent}
.badge.RED{background:var(--red-bg);color:var(--red);border-color:var(--red)}
.badge.YELLOW{background:var(--yellow-bg);color:var(--yellow);border-color:var(--yellow)}
.badge.GREEN{background:var(--green-bg);color:var(--green);border-color:var(--green)}
.badge.GREY{background:var(--grey-bg);color:var(--grey);border-color:var(--grey)}
.tag{display:inline-block;padding:0 5px;border:1px solid var(--line);border-radius:3px;font-size:11px;color:var(--muted);margin-left:4px;white-space:nowrap}
.bar{display:flex;height:14px;border-radius:3px;overflow:hidden;background:var(--grey-bg);min-width:160px}
.bar span{display:block;height:100%}
.bar .RED{background:var(--red)} .bar .YELLOW{background:var(--yellow)} .bar .GREEN{background:var(--green)} .bar .GREY{background:var(--grey);opacity:.45}
table{border-collapse:collapse;width:100%;background:var(--panel)}
th,td{text-align:left;padding:5px 8px;border-bottom:1px solid var(--line);vertical-align:top}
th{position:sticky;top:0;background:var(--panel);z-index:1;font-weight:600;cursor:pointer;user-select:none;white-space:nowrap}
th .dir{color:var(--muted);font-size:11px}
tr.op{cursor:pointer}
tr.op:hover{background:var(--code-bg)}
tr.op.open{background:var(--code-bg)}
td.num{text-align:right;font-variant-numeric:tabular-nums}
.toolbar{display:flex;flex-wrap:wrap;gap:8px;align-items:center;margin:10px 0;position:sticky;top:0;background:var(--bg);padding:8px 0;z-index:2}
.toolbar input[type=search]{flex:1 1 260px;min-width:200px;padding:6px 8px;border:1px solid var(--line);border-radius:4px;background:var(--panel);color:var(--ink);font-size:14px}
.toolbar select{padding:5px 6px;border:1px solid var(--line);border-radius:4px;background:var(--panel);color:var(--ink)}
.chip{padding:3px 9px;border:1px solid var(--line);border-radius:12px;background:var(--panel);color:var(--ink);cursor:pointer;font-size:12px}
.chip[aria-pressed="true"]{border-color:var(--accent);background:var(--code-bg);font-weight:600}
.count{color:var(--muted);font-size:13px;margin-left:auto}
button.plain{padding:6px 12px;border:1px solid var(--line);border-radius:4px;background:var(--panel);color:var(--ink);cursor:pointer}
.detail{background:var(--panel);border-left:3px solid var(--line);padding:10px 14px}
.detail .grid{display:grid;grid-template-columns:1fr 1fr;gap:16px}
@media (max-width:900px){.detail .grid{grid-template-columns:1fr}}
.finding{border:1px solid var(--line);border-radius:5px;padding:8px 10px;margin:8px 0}
.finding h4{margin:0 0 4px;font-size:13px}
.ev{margin:3px 0 3px 0;font-size:12px}
.ev .where{color:var(--muted)}
.hop{font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;font-size:12px;white-space:pre-wrap;margin:2px 0}
ul{margin:4px 0 4px 18px;padding:0}
.muted{color:var(--muted)}
.small{font-size:12px}
.themebar{float:right}
.hidden{display:none}
@media print{.toolbar,.themebar,button{display:none}.detail{break-inside:avoid}}
</style>
</head>
<body>
<main>
<div class="themebar"><button class="plain" id="theme" type="button">Toggle dark mode</button></div>
<h1 id="title"></h1>
<div id="banner" class="banner hidden"></div>
<p class="meta" id="meta"></p>

<h2>Summary</h2>
<div class="tiles" id="tiles"></div>
<div class="grid2" style="display:grid;grid-template-columns:repeat(auto-fit,minmax(420px,1fr));gap:16px">
  <div><h3>By domain</h3><table id="byDomain"></table></div>
  <div><h3>By kind</h3><table id="byKind"></table></div>
</div>

<h2>Operations</h2>
<div class="toolbar" role="search">
  <input type="search" id="q" placeholder="Search id, target, symbol, file, issue..." aria-label="Search operations">
  <span id="chips"></span>
  <select id="fDomain" aria-label="Domain"></select>
  <select id="fKind" aria-label="Kind"></select>
  <select id="fTier" aria-label="Analysed by"></select>
  <select id="fDiscovery" aria-label="Discovery status"></select>
  <span class="count" id="count"></span>
</div>
<table id="ops">
  <thead><tr id="head"></tr></thead>
  <tbody id="body"></tbody>
</table>
<p><button class="plain hidden" id="more" type="button">Show more</button></p>

<h2>Coverage and limits</h2>
<div id="coverage"></div>

<h2>Method</h2>
<div id="method">
<p><strong>What a colour means.</strong> Every row is one entry point of the application (HTTP endpoint, consumer, scheduled job, event listener, SOAP operation, job). The colour is computed by the report generator from the audit records; no model writes a colour into this page.</p>
<ul>
<li><span class="badge RED">RED · Broken</span> a verified finding of CRITICAL or HIGH severity (or an unverified one with HIGH confidence): acknowledged work can be lost, duplicated or corrupted. Needs work.</li>
<li><span class="badge YELLOW">YELLOW · Smells</span> something fragile that works today (lower severity, lower confidence, or a mitigation outside this repository), or a triage suspicion that still waits for a deep audit.</li>
<li><span class="badge GREEN">GREEN · OK</span> analysed and no open finding. At the <em>TRIAGE</em> tier this means "no risk signal in the entry method and one hop", not "proven safe". At <em>DEEP</em>/<em>VERIFIED</em> it means a flow was traced with file:line evidence and the audit rules found nothing.</li>
<li><span class="badge GREY">GREY · Not analysed</span> no verdict, or the verdict is stale because its files changed. The static hint column shows what the $0 pre-classification suspects; it never becomes a colour by itself.</li>
</ul>
<p><strong>Tiers.</strong> STATIC: deterministic scan and classification by the jar (every operation, $0). TRIAGE: a cheap model reads the entry method and one hop per batch of operations in one domain. DEEP: a flow is traced into an evidence file and audited rule by rule. VERIFIED: an independent agent in a fresh context tried to disprove each RED finding. Confidence is the audit's own estimate; any mitigation marked NOT_CHECKED caps it at MEDIUM.</p>
<p><strong>Evidence.</strong> Each finding cites file:line with a verbatim snippet. The jar re-reads those lines at the pinned commit (<code>verify-evidence</code>) and rejects mismatches, so no cited line is invented.</p>
<p><strong>Coverage.</strong> "No findings" is never "safe". The ledger above lists what ran, on what, for how much, and what did not run. Operations the plan skipped stay GREY.</p>
</div>
</main>
<script id="data" type="application/json">__DATA__</script>
<script>
(function(){
"use strict";
var D = JSON.parse(document.getElementById('data').textContent);
var COLOURS = ['RED','YELLOW','GREEN','GREY'];
var LABELS = {RED:'Broken',YELLOW:'Smells',GREEN:'OK',GREY:'Not analysed'};
var CHUNK = 250;

function esc(s){ return String(s == null ? '' : s).replace(/[&<>"']/g, function(c){ return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]; }); }
function badge(c, note){ return '<span class="badge '+c+'">'+c+' · '+LABELS[c]+'</span>' + (note ? '<span class="tag">'+esc(note)+'</span>' : ''); }
function el(id){ return document.getElementById(id); }
function pct(n, t){ return t ? Math.round(1000*n/t)/10 : 0; }

// theme
(function(){
  var saved = null; try { saved = localStorage.getItem('theme'); } catch(e){}
  if (saved) document.documentElement.setAttribute('data-theme', saved);
  el('theme').addEventListener('click', function(){
    var cur = document.documentElement.getAttribute('data-theme');
    var dark = cur ? cur === 'dark' : window.matchMedia('(prefers-color-scheme: dark)').matches;
    var next = dark ? 'light' : 'dark';
    document.documentElement.setAttribute('data-theme', next);
    try { localStorage.setItem('theme', next); } catch(e){}
  });
})();

// header
el('title').textContent = D.title;
document.title = D.title;
if (D.banner) { el('banner').textContent = D.banner; el('banner').classList.remove('hidden'); }
var m = D.manifest || {};
el('meta').innerHTML = 'Commit <code>' + esc((m.commit||'?').slice(0,12)) + '</code>' + (m.dirty ? ' (dirty)' : '') +
  ' · atlas scanned ' + esc(m.scannedAt||'?') + ' by ' + esc(m.scanner||'?') +
  ' · report generated ' + esc(D.generatedAt) + ' · audit status <strong>' + esc(D.summary.status) + '</strong>' +
  (D.summary.usdSpent != null ? ' · spent $' + D.summary.usdSpent.toFixed(2) + (D.summary.usdCap ? ' of $' + D.summary.usdCap.toFixed(2) : '') : '');

// summary tiles
var S = D.summary;
var tiles = COLOURS.map(function(c){ return '<div class="tile '+c+'"><div class="n">'+S.byColour[c]+'</div><div class="l">'+c+' · '+LABELS[c]+' · '+pct(S.byColour[c],S.total)+'%</div></div>'; });
tiles.push('<div class="tile"><div class="n">'+S.total+'</div><div class="l">operations in the atlas</div></div>');
tiles.push('<div class="tile"><div class="n">'+S.coveragePct+'%</div><div class="l">analysed by a model tier ('+S.analysed+' of '+S.total+')</div></div>');
tiles.push('<div class="tile"><div class="n">'+S.findings.verified+' / '+S.findings.candidate+'</div><div class="l">findings verified / candidate</div></div>');
tiles.push('<div class="tile"><div class="n">'+S.escalatedPending+'</div><div class="l">escalated by triage, deep audit pending</div></div>');
el('tiles').innerHTML = tiles.join('');

function breakdown(tableId, map, filterKey){
  var html = ['<tr><th>'+filterKey+'</th><th>total</th>'+COLOURS.map(function(c){return '<th>'+c+'</th>';}).join('')+'<th>distribution</th></tr>'];
  Object.keys(map).forEach(function(k){
    var v = map[k], t = COLOURS.reduce(function(a,c){return a+v[c];},0);
    html.push('<tr><td><a href="#" data-filter="'+esc(filterKey)+'" data-value="'+esc(k)+'">'+esc(k)+'</a></td><td class="num">'+t+'</td>' +
      COLOURS.map(function(c){return '<td class="num">'+v[c]+'</td>';}).join('') +
      '<td><div class="bar" title="'+COLOURS.map(function(c){return c+' '+v[c];}).join(', ')+'">' +
      COLOURS.map(function(c){return '<span class="'+c+'" style="width:'+pct(v[c],t)+'%"></span>';}).join('') + '</div></td></tr>');
  });
  el(tableId).innerHTML = html.join('');
  el(tableId).addEventListener('click', function(e){
    var a = e.target.closest('a[data-filter]'); if (!a) return;
    e.preventDefault();
    var sel = a.getAttribute('data-filter') === 'domain' ? el('fDomain') : el('fKind');
    sel.value = a.getAttribute('data-value'); apply(); el('ops').scrollIntoView();
  });
}
breakdown('byDomain', S.byDomain, 'domain');
breakdown('byKind', S.byKind, 'kind');

// operations table
var rows = D.rows.map(function(r, i){
  r._i = i;
  r._search = [r.id, r.kind, r.subkind, r.target, r.symbol, r.domain, r.file, r.topIssue, r.tier, r.colour, r.hint, r.signals.join(' ')].join(' ').toLowerCase();
  return r;
});
var COLS = [
  {key:'colour', label:'Colour', sort:function(r){ return COLOURS.indexOf(r.colour); }},
  {key:'kind', label:'Kind', sort:function(r){ return r.kind + '/' + r.subkind; }},
  {key:'target', label:'Target', sort:function(r){ return r.target; }},
  {key:'symbol', label:'Symbol', sort:function(r){ return r.symbol; }},
  {key:'domain', label:'Domain', sort:function(r){ return r.domain; }},
  {key:'topIssue', label:'Top issue', sort:function(r){ return r.topIssue; }},
  {key:'confidence', label:'Confidence', sort:function(r){ return {HIGH:0,MEDIUM:1,LOW:2}[r.confidence] == null ? 3 : {HIGH:0,MEDIUM:1,LOW:2}[r.confidence]; }},
  {key:'tier', label:'Analysed by', sort:function(r){ return {VERIFIED:0,DEEP:1,TRIAGE:2,STATIC:3}[r.tier]; }},
  {key:'score', label:'Rank', sort:function(r){ return -r.score; }}
];
var state = {colours:{RED:true,YELLOW:true,GREEN:true,GREY:true}, q:'', domain:'', kind:'', tier:'', discovery:'', sortKey:'score', sortDir:1, shown:CHUNK, open:{}};
var filtered = rows;

function fillSelect(id, label, values){
  var s = el(id); s.innerHTML = '<option value="">'+label+': all</option>' + values.map(function(v){ return '<option value="'+esc(v)+'">'+esc(v)+'</option>'; }).join('');
}
function uniq(key){ var o = {}; rows.forEach(function(r){ o[r[key]] = 1; }); return Object.keys(o).sort(); }
fillSelect('fDomain', 'Domain', uniq('domain'));
fillSelect('fKind', 'Kind', uniq('kind'));
fillSelect('fTier', 'Analysed by', ['VERIFIED','DEEP','TRIAGE','STATIC'].filter(function(t){ return rows.some(function(r){ return r.tier===t; }); }));
fillSelect('fDiscovery', 'Discovery', uniq('discovery'));
el('chips').innerHTML = COLOURS.map(function(c){ return '<button type="button" class="chip" data-colour="'+c+'" aria-pressed="true">'+c+' · '+LABELS[c]+' <span class="muted" id="chip-'+c+'"></span></button>'; }).join(' ');

el('head').innerHTML = COLS.map(function(c){ return '<th data-key="'+c.key+'">'+c.label+' <span class="dir"></span></th>'; }).join('');

function apply(){
  state.q = el('q').value.trim().toLowerCase();
  state.domain = el('fDomain').value; state.kind = el('fKind').value; state.tier = el('fTier').value; state.discovery = el('fDiscovery').value;
  var terms = state.q ? state.q.split(/\s+/) : [];
  filtered = rows.filter(function(r){
    if (!state.colours[r.colour]) return false;
    if (state.domain && r.domain !== state.domain) return false;
    if (state.kind && r.kind !== state.kind) return false;
    if (state.tier && r.tier !== state.tier) return false;
    if (state.discovery && r.discovery !== state.discovery) return false;
    for (var i=0;i<terms.length;i++) if (r._search.indexOf(terms[i]) < 0) return false;
    return true;
  });
  var col = COLS.filter(function(c){ return c.key === state.sortKey; })[0];
  filtered.sort(function(a,b){ var x = col.sort(a), y = col.sort(b); return (x<y?-1:x>y?1:a._i-b._i) * state.sortDir; });
  var counts = {RED:0,YELLOW:0,GREEN:0,GREY:0}; filtered.forEach(function(r){ counts[r.colour]++; });
  COLOURS.forEach(function(c){ el('chip-'+c).textContent = '(' + counts[c] + ')'; });
  state.shown = CHUNK;
  render();
}

function rowHtml(r){
  return '<tr class="op'+(state.open[r.id]?' open':'')+'" data-id="'+r.id+'" tabindex="0" aria-expanded="'+(!!state.open[r.id])+'">' +
    '<td>'+badge(r.colour, r.note)+(r.escalated && r.tier==='TRIAGE' ? '<span class="tag">escalated</span>' : '')+'</td>' +
    '<td>'+esc(r.kind)+'<br><span class="muted small">'+esc(r.subkind)+'</span></td>' +
    '<td><code>'+esc(r.target)+'</code>'+(r.placeholder?'<span class="tag">placeholder</span>':'')+'</td>' +
    '<td>'+esc(r.symbol)+'<br><span class="muted small">'+esc(r.file)+':'+r.line+(r.discovery!=='STATIC'?' · '+esc(r.discovery):'')+'</span></td>' +
    '<td>'+esc(r.domain)+'</td>' +
    '<td>'+esc(r.topIssue)+'</td>' +
    '<td>'+esc(r.confidence||'—')+'</td>' +
    '<td>'+esc(r.tier)+'</td>' +
    '<td class="num" title="static rank score; hint '+esc(r.hint)+'">'+r.score+'</td></tr>' +
    (state.open[r.id] ? '<tr class="detailrow" data-for="'+r.id+'"><td colspan="'+COLS.length+'"><div class="detail">'+detailHtml(r)+'</div></td></tr>' : '');
}

function render(){
  var slice = filtered.slice(0, state.shown);
  el('body').innerHTML = slice.map(rowHtml).join('');
  el('count').textContent = (slice.length < filtered.length ? 'showing ' + slice.length + ' of ' : '') + filtered.length + ' of ' + rows.length + ' operations';
  el('more').classList.toggle('hidden', slice.length >= filtered.length);
  Array.prototype.forEach.call(el('head').querySelectorAll('th'), function(th){
    th.querySelector('.dir').textContent = th.getAttribute('data-key') === state.sortKey ? (state.sortDir > 0 ? '▲' : '▼') : '';
  });
}

function evidenceHtml(e){
  return '<div class="ev"><span class="where">'+esc(e.file)+':'+e.line+(e.symbol?' · '+esc(e.symbol):'')+'</span>' +
    (e.claim ? ' — '+esc(e.claim) : '') + '<pre>'+esc(e.snippet)+'</pre></div>';
}

function findingHtml(f){
  var h = '<div class="finding"><h4>'+esc(f.ruleId)+' · '+esc(f.title||'')+' <span class="tag">'+esc(f.severity)+'</span><span class="tag">'+esc(f.status)+'</span><span class="tag">confidence '+esc(f.confidence)+'</span></h4>';
  h += '<p><strong>Failure scenario.</strong> '+esc(f.failureScenario)+'</p>';
  h += '<p><strong>Evidence.</strong></p>' + (f.evidence||[]).map(evidenceHtml).join('');
  if (f.mitigationsChecked && f.mitigationsChecked.length) h += '<p><strong>Mitigations checked.</strong></p><ul class="small">' + f.mitigationsChecked.map(function(mc){ return '<li>'+esc(mc.name)+': <strong>'+esc(mc.result)+'</strong>'+(mc.where?' <span class="muted">('+esc(mc.where)+')</span>':'')+'</li>'; }).join('') + '</ul>';
  h += '<p><strong>Fix.</strong> '+esc(f.recommendation)+'</p>';
  h += '<p><strong>How to verify.</strong></p><ul class="small">' + (f.verification||[]).map(function(v){ return '<li>'+esc(v)+'</li>'; }).join('') + '</ul>';
  if (f.blastRadius) h += '<p class="small muted">Blast radius: '+esc((f.blastRadius.domains||[]).join(', '))+' — '+esc((f.blastRadius.effects||[]).join('; '))+'</p>';
  if (f.verifiedBy) h += '<p class="small muted">Verified by '+esc(f.verifiedBy.model)+' in '+esc(f.verifiedBy.run)+': '+esc(f.verifiedBy.outcome)+(f.verifiedBy.note?' — '+esc(f.verifiedBy.note):'')+'</p>';
  return h + '</div>';
}

function flowHtml(flowId){
  if (!flowId) return '<p class="muted">No flow traced for this operation.</p>';
  var fl = D.flows[flowId];
  if (!fl) return '<p class="muted">Flow <code>'+esc(flowId)+'</code> is referenced by the verdict but its file was not found in the flows directory. The findings above still stand on their own evidence.</p>';
  var h = '<p><strong>'+esc(fl.id)+'</strong>'+(fl.title?' — '+esc(fl.title):'')+'</p>';
  h += '<div class="hop">'+esc(fl.entry.kind+' '+fl.entry.target+'  →  '+fl.entry.symbol)+'</div>';
  fl.hops.forEach(function(x){ h += '<div class="hop">'+esc('  '+x.order+'. '+x.symbol+(x.tx?'  ['+x.tx+']':'')+(x.thread?'  ('+x.thread+')':''))+' <span class="muted">'+esc(x.file.split('/').pop()+':'+x.line)+'</span></div>'; });
  h += '<p class="small"><strong>Boundaries</strong></p>';
  fl.boundaries.forEach(function(b){ h += '<div class="hop">'+esc('  '+b.order+'. '+b.type+'  '+b.symbol+(b.target?'  → '+b.target:'')+(b.tx?'  ['+b.tx+']':'')+'  durable: '+(b.durable===true?'yes':b.durable===false?'no':'?'))+' <span class="muted">'+esc(b.file.split('/').pop()+':'+b.line)+'</span></div>'; });
  if (fl.acknowledgement) h += '<p class="small"><strong>Acknowledgement</strong> (durable before: '+(fl.acknowledgement.durableBefore===true?'yes':fl.acknowledgement.durableBefore===false?'<strong>no</strong>':'unknown')+'): '+esc(fl.acknowledgement.description)+'</p>';
  if (fl.sourceBudget) h += '<p class="small muted">Files opened: '+fl.sourceBudget.filesOpened+' of '+fl.sourceBudget.limit+'</p>';
  if (fl.openQuestions && fl.openQuestions.length) h += '<p class="small"><strong>Open questions</strong></p><ul class="small">'+fl.openQuestions.map(function(q){ return '<li>'+esc(q)+'</li>'; }).join('')+'</ul>';
  return h;
}

function detailHtml(r){
  var v = r.verdict;
  var h = '<div class="grid"><div>';
  h += '<p>'+badge(r.colour, r.note)+' <strong>'+esc(r.id)+'</strong> · '+esc(r.kind)+'/'+esc(r.subkind)+' <code>'+esc(r.target)+'</code></p>';
  h += '<p>'+esc(r.symbol)+' — <code>'+esc(r.file)+':'+r.line+'</code> · discovery '+esc(r.discovery)+' · domain '+esc(r.domain)+'</p>';
  if (v) {
    h += '<p><strong>Verdict ('+esc(v.tier)+', '+esc(v.model||'')+').</strong> '+esc(v.summary||'')+(v.note?' <span class="muted">'+esc(v.note)+'</span>':'')+'</p>';
    if (v.reasons && v.reasons.length) h += '<ul class="small">'+v.reasons.map(function(x){ return '<li>'+esc(x.text)+(x.evidence?' — <span class="muted">'+esc(x.evidence.file.split('/').pop()+':'+x.evidence.line)+'</span><pre>'+esc(x.evidence.snippet)+'</pre>':'')+'</li>'; }).join('')+'</ul>';
    if (v.escalate && r.tier === 'TRIAGE') h += '<p class="small"><strong>Escalated</strong> to a deep audit that has not run yet; see the coverage ledger.</p>';
    h += '<p class="small muted">Run '+esc(v.run||'?')+(v.usd!=null?' · $'+v.usd.toFixed(2):'')+(v.fingerprint?' · verdict depends on '+v.fingerprint.files.length+' file(s)':'')+'</p>';
  } else {
    h += '<p><strong>Not analysed by any model tier.</strong> Only the static pre-classification ran.</p>';
  }
  h += '<p class="small"><strong>Static pre-classification</strong> ($0): hint <code>'+esc(r.hint)+'</code>, confidence '+r.hintConfidence+', rank score '+r.score+', signal scope '+esc(r.signalScope)+'.</p>';
  if (r.hintReasons.length) h += '<ul class="small">'+r.hintReasons.map(function(x){ return '<li>'+esc(x)+'</li>'; }).join('')+'</ul>';
  h += '<p class="small muted">Signals: '+(r.signals.length ? r.signals.map(function(s){ return '<code>'+esc(s)+'</code>'; }).join(' ') : 'none')+(r.reachedFiles.length?'<br>Reached files: '+r.reachedFiles.map(function(f){ return esc(f.split('/').pop()); }).join(', '):'')+'</p>';
  h += '</div><div><h3>Flow sketch</h3>' + flowHtml(v ? v.flowId : null) + '</div></div>';
  if (r.findings.length) h += '<h3>Findings ('+r.findings.length+')</h3>' + r.findings.map(findingHtml).join('');
  return h;
}

el('body').addEventListener('click', function(e){
  var tr = e.target.closest('tr.op'); if (!tr) return;
  toggle(tr.getAttribute('data-id'));
});
el('body').addEventListener('keydown', function(e){
  if (e.key !== 'Enter' && e.key !== ' ') return;
  var tr = e.target.closest('tr.op'); if (!tr) return;
  e.preventDefault(); toggle(tr.getAttribute('data-id'));
});
function toggle(id){
  var r = rows.filter(function(x){ return x.id === id; })[0]; if (!r) return;
  var tr = el('body').querySelector('tr.op[data-id="'+id+'"]');
  var det = el('body').querySelector('tr.detailrow[data-for="'+id+'"]');
  if (det) { det.parentNode.removeChild(det); tr.classList.remove('open'); tr.setAttribute('aria-expanded','false'); delete state.open[id]; return; }
  state.open[id] = true;
  tr.classList.add('open'); tr.setAttribute('aria-expanded','true');
  tr.insertAdjacentHTML('afterend', '<tr class="detailrow" data-for="'+id+'"><td colspan="'+COLS.length+'"><div class="detail">'+detailHtml(r)+'</div></td></tr>');
}

var debounce;
el('q').addEventListener('input', function(){ clearTimeout(debounce); debounce = setTimeout(apply, 120); });
['fDomain','fKind','fTier','fDiscovery'].forEach(function(id){ el(id).addEventListener('change', apply); });
el('chips').addEventListener('click', function(e){
  var b = e.target.closest('button[data-colour]'); if (!b) return;
  var c = b.getAttribute('data-colour'); state.colours[c] = !state.colours[c];
  b.setAttribute('aria-pressed', String(state.colours[c])); apply();
});
el('head').addEventListener('click', function(e){
  var th = e.target.closest('th[data-key]'); if (!th) return;
  var k = th.getAttribute('data-key');
  if (state.sortKey === k) state.sortDir = -state.sortDir; else { state.sortKey = k; state.sortDir = 1; }
  apply();
});
el('more').addEventListener('click', function(){ state.shown += CHUNK; render(); });
if (location.hash && location.hash.length > 1) { state.open[location.hash.slice(1)] = true; el('q').value = location.hash.slice(1); }
apply();

// coverage
(function(){
  var c = D.coverage || {}, h = '';
  h += '<div class="tiles">';
  var bt = S.byTier;
  ['STATIC','TRIAGE','DEEP','VERIFIED'].forEach(function(t){ h += '<div class="tile"><div class="n">'+(bt[t]||0)+'</div><div class="l">operations whose best tier is '+t+'</div></div>'; });
  h += '<div class="tile"><div class="n">'+(c.pending||[]).length+'</div><div class="l">planned, not run</div></div>';
  h += '</div>';
  if (c.budget) h += '<p>Budget: spent $'+(c.budget.usdSpent||0).toFixed(2)+(c.budget.usdCap?' of $'+c.budget.usdCap.toFixed(2):'')+'. '+esc(c.budget.stoppedBecause||'')+'</p>';
  if (c.plan) h += '<p class="small muted">Ranking: '+esc(c.plan.rankedBy||'')+(c.plan.deepCutoffScore!=null?'; deep-audit cut-off score '+c.plan.deepCutoffScore:'')+'.</p>';
  if (c.runs && c.runs.length) {
    h += '<h3>Runs</h3><table><tr><th>Run</th><th>Tier</th><th>Agent / tool</th><th>Model</th><th>Scope</th><th>USD</th><th>Status</th></tr>';
    c.runs.forEach(function(r){
      var scope = r.operations === 'all' ? 'all operations' : (r.operations ? r.operations.length + ' operation(s)' + (r.domain ? ' in ' + r.domain : '') : '') + (r.findings ? r.findings.length + ' finding(s)' : '') + (r.flowId ? ' · ' + r.flowId : '');
      h += '<tr><td>'+esc(r.id)+'</td><td>'+esc(r.tier)+'</td><td>'+esc(r.agent||r.tool||'')+'</td><td class="small">'+esc(r.model||'—')+'</td><td class="small">'+esc(scope)+'</td><td class="num">'+(r.usd!=null?r.usd.toFixed(2):'—')+'</td><td>'+esc(r.status)+(r.reason?' <span class="muted">('+esc(r.reason)+')</span>':'')+'</td></tr>';
    });
    h += '</table>';
  }
  if (c.profiles && c.profiles.length) h += '<p class="small"><strong>Audit profiles applied:</strong> '+c.profiles.map(function(p){ return esc(p.id)+' ('+esc((p.rules||[]).join(', '))+')'; }).join('; ')+'</p>';
  if (c.pending && c.pending.length) h += '<h3>Planned but not run</h3><ul class="small">'+c.pending.map(function(p){ return '<li><a href="#'+esc(p.id)+'">'+esc(p.id)+'</a> — '+esc(p.reason)+'</li>'; }).join('')+'</ul>';
  var limits = (c.limits||[]).concat((S.warnings||[]).map(function(w){ return 'Atlas warning: ' + w; }));
  if (limits.length) h += '<h3>Limits</h3><ul class="small">'+limits.map(function(l){ return '<li>'+esc(l)+'</li>'; }).join('')+'</ul>';
  if (!c.runs) h += '<p><strong>No audit has run.</strong> Every operation is GREY; only the static scan exists.</p>';
  el('coverage').innerHTML = h;
})();
})();
</script>
</body>
</html>
"""


if __name__ == "__main__":
    main()
