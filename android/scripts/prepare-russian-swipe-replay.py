#!/usr/bin/env python3
"""Convert the Yandex Cup NeuroSwipe validation set to DeviceChecks input."""
import argparse
import json
from pathlib import Path


ALPHABET = set("абвгдеёжзийклмнопрстуфхцчшщъыьэюя")


def read_gestures(curves, answers):
    layouts = []
    layout_ids = {}
    gestures = []
    for index, (line, answer) in enumerate(zip(curves, answers, strict=True)):
        curve = json.loads(line)["curve"]
        word = answer.strip().lower()
        if len(curve["x"]) < 2 or len(word) < 2:
            continue
        keys = {}
        for key in curve["grid"]["keys"]:
            label = key.get("label", "").lower()
            if label in ALPHABET:
                box = key["hitbox"]
                keys.setdefault(label, (box["x"] + box["w"] / 2, box["y"] + box["h"] / 2))
        fingerprint = tuple(sorted(keys.items()))
        if fingerprint not in layout_ids:
            layout_ids[fingerprint] = len(layouts)
            layouts.append([keys, abs(keys["й"][0] - keys["ц"][0]), abs(keys["й"][1] - keys["ф"][1])])
        gestures.append([index, layout_ids[fingerprint], word, curve["x"], curve["y"], curve["t"]])
    return layouts, gestures


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("curves", type=Path)
    parser.add_argument("answers", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    with args.curves.open(encoding="utf-8") as curves, args.answers.open(encoding="utf-8") as answers:
        layouts, gestures = read_gestures(curves, answers)
    with args.output.open("w", encoding="utf-8") as output:
        for row in [layouts, *gestures]:
            output.write(json.dumps(row, ensure_ascii=False, separators=(",", ":")) + "\n")
    print(f"Prepared {len(gestures):,} gestures on {len(layouts)} layouts")


if __name__ == "__main__":
    main()
