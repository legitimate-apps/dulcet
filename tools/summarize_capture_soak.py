#!/usr/bin/env python3
"""Publish capture-soak statistics without conflating output failure with divergence."""

from __future__ import annotations

import argparse
import os
from pathlib import Path
import sys


SUMMARY_FAILURE = 3


def bounded_integer(raw: str, label: str, lower: int, upper: int) -> int:
    try:
        value = int(raw)
    except ValueError as error:
        raise ValueError(f"{label} must be an integer") from error
    if not lower <= value <= upper:
        raise ValueError(f"{label} must be between {lower} and {upper}")
    return value


def output_path(variable: str) -> Path:
    raw = os.environ.get(variable, "")
    if not raw:
        raise ValueError(f"{variable} is required")
    return Path(raw)


def format_probability(value: float) -> str:
    """Keep six significant digits, including scientific notation for tiny nonzero values."""
    if not 0.0 < value <= 1.0:
        raise ValueError("historical zero-divergence probability is outside (0, 1]")
    rendered = f"{value:.6g}"
    if float(rendered) <= 0.0:
        raise ValueError("historical zero-divergence probability rounded to zero")
    return rendered


def summarize(arguments: argparse.Namespace) -> None:
    pair_count = bounded_integer(arguments.pair_count, "pair-count", 1, 100)
    divergences = bounded_integer(arguments.divergences, "divergences", 0, pair_count)
    elapsed = bounded_integer(arguments.elapsed_seconds, "elapsed-seconds", 0, 86_400)
    results = Path(arguments.results)
    if not results.is_dir():
        raise ValueError(f"results directory does not exist: {results}")

    matches = pair_count - divergences
    divergence_rate = f"{divergences / pair_count:.6f}"
    # Conditional model only: the measured pre-fix rate was 20% per pair. This does not assert
    # that future pairs are independent or stationary.
    historical_zero_probability = format_probability(0.8**pair_count)
    artifact = (
        f"CAPTURE SOAK RESULT pairs={pair_count} fresh-processes={pair_count * 2} "
        f"matches={matches} divergences={divergences} divergence-rate={divergence_rate} "
        f"elapsed-seconds={elapsed}\n"
        f"CAPTURE SOAK CONTEXT probability-of-zero-at-historical-20-percent-rate="
        f"{historical_zero_probability}\n"
    )
    (results / "summary.txt").write_text(artifact, encoding="utf-8")
    print(artifact, end="")

    step_summary = (
        "## macOS deterministic-capture soak\n\n"
        f"- Independent pairs: `{pair_count}`\n"
        f"- Fresh capture processes: `{pair_count * 2}`\n"
        f"- Matching pairs: `{matches}`\n"
        f"- Divergent pairs: `{divergences}`\n"
        f"- Divergence rate: `{divergence_rate}`\n"
        f"- Elapsed seconds: `{elapsed}`\n"
        "- Probability of zero divergences if the historical 20% pair rate still holds: "
        f"`{historical_zero_probability}`\n"
    )
    with output_path("GITHUB_STEP_SUMMARY").open("a", encoding="utf-8") as stream:
        stream.write(step_summary)
    with output_path("GITHUB_OUTPUT").open("a", encoding="utf-8") as stream:
        stream.write(f"pairs={pair_count}\ndivergences={divergences}\n")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--results", required=True)
    parser.add_argument("--pair-count", required=True)
    parser.add_argument("--divergences", required=True)
    parser.add_argument("--elapsed-seconds", required=True)
    arguments = parser.parse_args()
    try:
        summarize(arguments)
    except (OSError, ValueError) as error:
        print(f"CAPTURE SOAK SUMMARY ERROR {error}", file=sys.stderr)
        return SUMMARY_FAILURE
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
