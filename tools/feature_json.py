"""Strict JSON decoding for the submitted feature matrix."""

import json


def unique_object(pairs: list[tuple[str, object]]) -> dict:
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"duplicate JSON key: {key}")
        result[key] = value
    return result


def loads(text: str) -> dict:
    return json.loads(text, object_pairs_hook=unique_object)
