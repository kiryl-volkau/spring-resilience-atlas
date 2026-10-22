"""Static pre-classification of every atlas entry point (tier A, no LLM).

Spec for a future `classify` command in resilience-atlas.jar. Reads the atlas and the
repository sources, writes `<audit>/operations.jsonl`: one record per entry point with
static side-effect signals, a preliminary colour hint, a confidence and a rank score.

Usage:
    py -I classify.py --atlas <atlas-dir> --repo <spring-repo> --domains <domains.jsonl> --out <audit-dir>

PROTOTYPE: signals are regex matches over the whole file that declares the entry point
(scope "file"), because the atlas has no side-effect tables yet. The jar will compute them
per method from the AST (scope "method") and from transactions/persistence/messaging tables.
"""

import argparse
import json
import re
from pathlib import Path

SIGNALS = {
    "transactional": r"@Transactional\b",
    "dbWrite": r"\.(save|saveAll|saveAndFlush|delete|deleteById|deleteAll|persist|merge)\s*\(|JdbcTemplate|@Modifying",
    "messageSend": r"\w*Template\.(send|convertAndSend|sendDefault)\s*\(|streamBridge\.send|publishEvent\(",
    "asyncFork": r"executor\.(submit|execute)\s*\(|@Async\b|CompletableFuture\.(runAsync|supplyAsync)|new Thread\(",
    "externalCall": r"restTemplate\.|webClient\.|@FeignClient|\w+Client\.\w+\(",
    "manualAck": r"deleteMessage\(|\.acknowledge\(",
    "concurrencyGuard": r"@DisallowConcurrentExecution|@SchedulerLock|ShedLock|LockProvider",
    "idempotencyCheck": r"findBy\w*(EventId|IdempotencyKey|ExternalId|ExternalReference)|existsBy|Idempotency-Key",
    "retry": r"@Retryable|@Retry\b|RetryTemplate|@CircuitBreaker",
    "inMemoryExecutor": r"Executors\.new\w*Pool|new ThreadPoolExecutor",
    "autoCommitConsumer": r"ENABLE_AUTO_COMMIT_CONFIG,\s*\"true\"",
    "clientWithoutTimeouts": r"new RestTemplate\(\)",
}

MUTATING_HTTP = {"POST", "PUT", "PATCH", "DELETE", "ANY"}

KIND_BASE = {"MESSAGING": 25, "SCHEDULED": 20, "JOB": 20, "EVENT": 15, "SOAP": 20, "ROUTER": 10, "HTTP": 5}
CRITICALITY_BONUS = {"MONEY": 20, "STATE": 15, "NORMAL": 5, "LOW": 0}
SIGNAL_WEIGHTS = {
    "dbWrite": 15, "messageSend": 15, "asyncFork": 20, "externalCall": 15,
    "inMemoryExecutor": 10, "autoCommitConsumer": 10, "clientWithoutTimeouts": 10,
}


def load_jsonl(path):
    with open(path, encoding="utf-8") as f:
        return [json.loads(line) for line in f if line.strip()]


def domain_of(cls, domains):
    best = None
    for d in domains:
        for prefix in d["packages"]:
            if cls.startswith(prefix + ".") and (best is None or len(prefix) > len(best[0])):
                best = (prefix, d)
    return best[1] if best else None


def file_signals(repo, rel_file, cache):
    if rel_file in cache:
        return cache[rel_file]
    try:
        text = (repo / rel_file).read_text(encoding="utf-8", errors="replace")
    except OSError:
        text = ""
    found = {name: bool(re.search(rx, text)) for name, rx in SIGNALS.items()}
    cache[rel_file] = found
    return found


DEPENDENCY_FIELD = re.compile(r"private\s+(?:final\s+)?([A-Z]\w*)(?:<[^>]*>)?\s+\w+\s*;")


def dependency_files(repo, rel_file, types_by_simple_name):
    """Files of the types injected into the entry point's class: one hop down the call graph.

    Reliability defects usually sit in the service the controller or listener delegates to,
    so signals from the entry file alone under-rank the entry point.
    """
    try:
        text = (repo / rel_file).read_text(encoding="utf-8", errors="replace")
    except OSError:
        return []
    files = []
    for simple in DEPENDENCY_FIELD.findall(text):
        for t in types_by_simple_name.get(simple, []):
            if t["file"] != rel_file:
                files.append(t["file"])
    return sorted(set(files))


def merged_signals(repo, ep, types_by_simple_name, cache):
    own = file_signals(repo, ep["file"], cache)
    hops = dependency_files(repo, ep["file"], types_by_simple_name)
    merged = dict(own)
    for hop_file in hops:
        for name, value in file_signals(repo, hop_file, cache).items():
            merged[name] = merged[name] or value
    merged["scope"] = "file+1hop" if hops else "file"
    merged["reachedFiles"] = hops
    return merged


def is_mutating(ep):
    if ep["kind"] == "HTTP":
        return any(m in MUTATING_HTTP for m in ep["subkind"].split(","))
    if ep["kind"] == "SOAP":
        return not ep["method"].lower().startswith(("get", "find", "list"))
    return True


def preliminary(ep, sig, mutating):
    reasons = []
    hint = "UNRATED"
    if ep["kind"] == "HTTP" and not mutating and not (sig["dbWrite"] or sig["messageSend"] or sig["asyncFork"]):
        return "LIKELY_GREEN", ["read-only HTTP method, no write signals in file"]
    if sig["asyncFork"]:
        reasons.append("async fork onto an in-process thread" + (" inside @Transactional scope" if sig["transactional"] else ""))
        hint = "LIKELY_RED"
    if sig["transactional"] and sig["dbWrite"] and sig["messageSend"]:
        reasons.append("DB write and message send in one file with @Transactional (dual write unless outbox)")
        hint = "LIKELY_RED"
    if sig["transactional"] and sig["externalCall"]:
        reasons.append("external call in a file with @Transactional (remote call inside DB transaction?)")
        hint = "LIKELY_RED"
    if hint == "UNRATED":
        if ep["kind"] == "MESSAGING" and not sig["idempotencyCheck"]:
            reasons.append("consumer without an idempotency lookup")
            hint = "LIKELY_YELLOW"
        if ep["kind"] in ("SCHEDULED", "JOB") and not sig["concurrencyGuard"]:
            reasons.append("scheduled work without a concurrency guard (ShedLock, DisallowConcurrentExecution)")
            hint = "LIKELY_YELLOW"
        if sig["externalCall"] and not sig["retry"]:
            reasons.append("external call without retry/circuit-breaker annotations")
            hint = "LIKELY_YELLOW"
        if sig["autoCommitConsumer"]:
            reasons.append("Kafka consumer with enable.auto.commit=true (at-most-once)")
            hint = "LIKELY_YELLOW"
    return hint, reasons


def score(ep, sig, domain, mutating):
    s = KIND_BASE.get(ep["kind"], 5)
    if ep["kind"] == "HTTP" and mutating:
        s += 15
    s += sum(w for name, w in SIGNAL_WEIGHTS.items() if sig[name])
    s += CRITICALITY_BONUS.get((domain or {}).get("criticality", "NORMAL"), 5)
    if ep["status"] != "STATIC":
        s += 5
    return min(s, 100)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--atlas", required=True)
    ap.add_argument("--repo", required=True)
    ap.add_argument("--domains", required=True)
    ap.add_argument("--out", required=True)
    args = ap.parse_args()

    atlas, repo, out = Path(args.atlas), Path(args.repo), Path(args.out)
    manifest = json.loads((atlas / "manifest.json").read_text(encoding="utf-8"))
    entrypoints = load_jsonl(atlas / "entrypoints.jsonl")
    domains = load_jsonl(args.domains)
    types_by_simple_name = {}
    for t in load_jsonl(atlas / "types.jsonl"):
        types_by_simple_name.setdefault(t["fqn"].split(".")[-1], []).append(t)
    eps_per_file = {}
    for ep in entrypoints:
        eps_per_file[ep["file"]] = eps_per_file.get(ep["file"], 0) + 1

    cache = {}
    out.mkdir(parents=True, exist_ok=True)
    with open(out / "operations.jsonl", "w", encoding="utf-8") as f:
        for ep in entrypoints:
            sig = merged_signals(repo, ep, types_by_simple_name, cache)
            domain = domain_of(ep["cls"], domains)
            mutating = is_mutating(ep)
            hint, reasons = preliminary(ep, sig, mutating)
            confidence = 0.35 + (0.15 if eps_per_file[ep["file"]] == 1 else 0.0)
            if sig["reachedFiles"]:
                confidence -= 0.1
            if ep["status"] == "UNKNOWN":
                hint, reasons, confidence = "UNRATED", ["target could not be resolved statically"], 0.1
            record = {
                "id": ep["id"],
                "commit": manifest["commit"],
                "domain": domain["domain"] if domain else None,
                "kind": ep["kind"],
                "subkind": ep["subkind"],
                "target": ep["target"],
                "symbol": ep["cls"].split(".")[-1] + "." + ep["method"],
                "cls": ep["cls"],
                "file": ep["file"],
                "line": ep["line"],
                "discovery": ep["status"],
                "via": ep["via"],
                "placeholder": ep["placeholder"],
                "mutating": mutating,
                "signals": sig,
                "preliminary": {
                    "hint": hint,
                    "confidence": round(confidence, 2),
                    "score": score(ep, sig, domain, mutating),
                    "reasons": reasons,
                },
            }
            f.write(json.dumps(record, ensure_ascii=False) + "\n")
    print(f"# wrote {len(entrypoints)} operations to {out / 'operations.jsonl'}")


if __name__ == "__main__":
    main()
