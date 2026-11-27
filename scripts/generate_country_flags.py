#!/usr/bin/env python3
"""Fetch and package circular country flag assets for the V2 Plas Android build.

Source artwork: HatScripts/circle-flags (MIT), distributed as circular SVGs.
The generated PNGs are app resources: no flag is downloaded at runtime.
"""
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path
from urllib.request import Request, urlopen
import argparse
import subprocess
import sys
import tempfile
import time

COUNTRY_CODES = ["ad", "ae", "af", "ag", "ai", "al", "am", "ao", "aq", "ar", "as", "at", "au", "aw", "ax", "az", "ba", "bb", "bd", "be", "bf", "bg", "bh", "bi", "bj", "bl", "bm", "bn", "bo", "bq", "br", "bs", "bt", "bv", "bw", "by", "bz", "ca", "cc", "cd", "cf", "cg", "ch", "ci", "ck", "cl", "cm", "cn", "co", "cr", "cu", "cv", "cw", "cx", "cy", "cz", "de", "dj", "dk", "dm", "do", "dz", "ec", "ee", "eg", "eh", "er", "es", "et", "fi", "fj", "fk", "fm", "fo", "fr", "ga", "gb", "gd", "ge", "gf", "gg", "gh", "gi", "gl", "gm", "gn", "gp", "gq", "gr", "gs", "gt", "gu", "gw", "gy", "hk", "hm", "hn", "hr", "ht", "hu", "id", "ie", "il", "im", "in", "io", "iq", "ir", "is", "it", "je", "jm", "jo", "jp", "ke", "kg", "kh", "ki", "km", "kn", "kp", "kr", "kw", "ky", "kz", "la", "lb", "lc", "li", "lk", "lr", "ls", "lt", "lu", "lv", "ly", "ma", "mc", "md", "me", "mf", "mg", "mh", "mk", "ml", "mm", "mn", "mo", "mp", "mq", "mr", "ms", "mt", "mu", "mv", "mw", "mx", "my", "mz", "na", "nc", "ne", "nf", "ng", "ni", "nl", "no", "np", "nr", "nu", "nz", "om", "pa", "pe", "pf", "pg", "ph", "pk", "pl", "pm", "pn", "pr", "ps", "pt", "pw", "py", "qa", "re", "ro", "rs", "ru", "rw", "sa", "sb", "sc", "sd", "se", "sg", "sh", "si", "sj", "sk", "sl", "sm", "sn", "so", "sr", "ss", "st", "sv", "sx", "sy", "sz", "tc", "td", "tf", "tg", "th", "tj", "tk", "tl", "tm", "tn", "to", "tr", "tt", "tv", "tw", "tz", "ua", "ug", "um", "us", "uy", "uz", "va", "vc", "ve", "vg", "vi", "vn", "vu", "wf", "ws", "ye", "yt", "za", "zm", "zw", "xk"]
BASE_URLS = (
    "https://hatscripts.github.io/circle-flags/flags/{code}.svg",
    "https://raw.githubusercontent.com/HatScripts/circle-flags/gh-pages/flags/{code}.svg",
)


def fetch_svg(code: str) -> bytes:
    errors = []
    for base in BASE_URLS:
        url = base.format(code=code)
        for attempt in range(3):
            try:
                request = Request(url, headers={"User-Agent": "V2Plas-Country-Flag-Builder/1.0"})
                with urlopen(request, timeout=25) as response:
                    data = response.read()
                if b"<svg" not in data[:2048].lower():
                    raise ValueError("response is not an SVG document")
                return data
            except Exception as exc:
                errors.append(f"{url}: {exc}")
                time.sleep(0.4 * (attempt + 1))
    raise RuntimeError("; ".join(errors))


def build_one(code: str, output: Path) -> None:
    svg = fetch_svg(code)
    with tempfile.NamedTemporaryFile(suffix=".svg", delete=False) as tmp:
        tmp.write(svg)
        svg_path = Path(tmp.name)
    png_path = output / f"flag_{code}.png"
    try:
        subprocess.run(
            ["rsvg-convert", "--width", "192", "--height", "192", "--output", str(png_path), str(svg_path)],
            check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
        )
        if not png_path.is_file() or png_path.stat().st_size < 128:
            raise RuntimeError("renderer produced an empty or invalid PNG")
    finally:
        svg_path.unlink(missing_ok=True)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)

    failures = []
    with ThreadPoolExecutor(max_workers=10) as pool:
        jobs = {pool.submit(build_one, code, args.output): code for code in COUNTRY_CODES}
        for job in as_completed(jobs):
            code = jobs[job]
            try:
                job.result()
                print(f"flag ready: {code.upper()}")
            except Exception as exc:
                failures.append((code, str(exc)))
                print(f"::error::Flag {code.upper()} failed: {exc}", file=sys.stderr)

    if failures:
        missing = ", ".join(code.upper() for code, _ in failures)
        print(f"::error::Could not generate all country flags. Missing: {missing}", file=sys.stderr)
        return 1

    actual = list(args.output.glob("flag_*.png"))
    if len(actual) != len(COUNTRY_CODES):
        print(f"::error::Expected {len(COUNTRY_CODES)} flags, found {len(actual)}", file=sys.stderr)
        return 1
    print(f"Generated all {len(actual)} bundled circular country flags (249 ISO countries + XK).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
