"""Run the bridge through pocket_runner on this PC, against the real API, with the test key.

    python3 tools/desktop_run.py --minutes 6

Alerts the phone would get go to run/desktop/alerts.jsonl and the terminal. Also counts the
API requests and bytes, to estimate the phone's data use. Folder: run/desktop (private).
"""
import argparse
import gzip
import json
import sys
import threading
import time
from pathlib import Path

import requests

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "android" / "app" / "src" / "main" / "python"))
import pocket_checks  # noqa: E402
import pocket_runner  # noqa: E402

traffic = {"requests": 0, "not_modified": 0, "errors": 0, "bytes": 0, "by_status": {}, "per_minute": {}}
STARTED = time.monotonic()
_original_request = requests.Session.request


def counting_request(session, method, url, *args, **kwargs):
    response = _original_request(session, method, url, *args, **kwargs)
    if "solthegame.com" in url or "github" in url:
        traffic["requests"] += 1
        traffic["by_status"][response.status_code] = traffic["by_status"].get(response.status_code, 0) + 1
        if response.status_code == 304:
            traffic["not_modified"] += 1
        # Bytes as they crossed the network (compressed), plus a rough allowance for headers.
        # requests has already unzipped the body, so re-gzip it to estimate what crossed the
        # network (the API sends gzip; checked by hand within ~10%).
        body = response.content
        wire = len(gzip.compress(body, 6)) if body and response.headers.get("Content-Encoding") == "gzip" else len(body)
        size = wire + 800
        path = url.split("solthegame.com", 1)[-1].split("?")[0] or url
        count, total, unzipped = traffic.setdefault("by_path", {}).get(path, (0, 0, 0))
        traffic["by_path"][path] = (count + 1, total + size, unzipped + len(response.content))
        traffic["bytes"] += size
        minute = int((time.monotonic() - STARTED) // 60)
        reads, total = traffic["per_minute"].get(minute, (0, 0))
        traffic["per_minute"][minute] = (reads + 1, total + size)
    return response


class FileSender:
    def __init__(self, path):
        self.handle = open(path, "a", encoding="utf-8")
        self.last_status = None

    def deliver(self, channel, header, text, plain, priority):
        record = {"at": time.strftime("%H:%M:%S"), "channel": channel, "header": header,
                  "priority": priority, "text": text, "plain": plain}
        self.handle.write(json.dumps(record, ensure_ascii=False) + "\n")
        self.handle.flush()
        mark = "LOUD " if priority == "urgent" else "quiet"
        print(f"  → [{mark} {channel}] {plain.splitlines()[0][:150]}", flush=True)

    def status(self, text):
        self.last_status = text


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--minutes", type=float, default=6)
    parser.add_argument("--preset", choices=("recommended", "saver", "fast"), default="recommended")
    parser.add_argument("--set", action="append", default=[], metavar="Check=seconds",
                        help="override one check, e.g. --set ChatWatcher=60 (0 = off)")
    args = parser.parse_args()
    checks = pocket_checks.preset(args.preset)
    for item in args.set:
        name, _, seconds = item.partition("=")
        checks[name] = int(seconds)

    folder = ROOT / "run" / "desktop"
    folder.mkdir(parents=True, exist_ok=True)
    folder.chmod(0o700)
    key = (ROOT / "secrets" / "test-api-key").read_text().strip()
    sender = FileSender(folder / "alerts.jsonl")
    runner = pocket_runner.Runner(folder, key, sender, checks=checks)

    requests.Session.request = counting_request
    timer = threading.Timer(args.minutes * 60, runner.stop)
    timer.start()
    started = time.monotonic()
    try:
        runner.run()
    finally:
        timer.cancel()
        minutes = (time.monotonic() - started) / 60
        print("\n===== desktop run summary =====")
        print(f"ran {minutes:.1f} min | bridge {runner.module.VERSION if runner.module else '?'}")
        print(f"last status: {sender.last_status}")
        print(f"HTTP requests: {traffic['requests']} ({traffic['requests'] / max(minutes, 0.1):.1f}/min), "
              f"304 unchanged: {traffic['not_modified']}, by status: {traffic['by_status']}")
        print(f"approx. data: {traffic['bytes'] / 1024:.0f} KiB")
        print("per minute (requests, KiB): " + ", ".join(
            f"{m}: {r}/{b / 1024:.0f}" for m, (r, b) in sorted(traffic["per_minute"].items())))
        print("by path (requests, KiB on the wire, KiB unzipped):")
        for path, (count, total, unzipped) in sorted(traffic.get("by_path", {}).items(), key=lambda kv: -kv[1][1]):
            print(f"  {path[:60]:<60} {count:>4} {total / 1024:>8.0f} {unzipped / 1024:>8.0f}")
        steady = [v for m, v in traffic["per_minute"].items() if m >= 2]
        if steady:
            reads = sum(r for r, _ in steady) / len(steady)
            kib = sum(b for _, b in steady) / len(steady) / 1024
            print(f"after startup: {reads:.1f} requests/min, {kib:.0f} KiB/min "
                  f"(≈ {kib * 60 * 24 * 30 / 1024 / 1024:.2f} GiB per 30 days)")


if __name__ == "__main__":
    main()
