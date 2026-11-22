#!/usr/bin/env python3
"""Convert the bundled glide vocabulary and adjacent-word counts to HXTM v3."""

import json
import math
import struct
from collections import Counter, defaultdict
from pathlib import Path


ASSETS = Path(__file__).resolve().parents[1] / "app/src/main/assets"
ALPHABETS = {
    "ru": "абвгдеёжзийклмнопрстуфхцчшщъыьэюя",
    "uk": "абвгґдеєжзиіїйклмнопрстуфхцчшщьюя'",
}


def cost(probability):
    return min(255, round(-8 * math.log(min(1.0, probability))))


def model(language, alphabet):
    codes = {letter: number for number, letter in enumerate(sorted(alphabet), 1)}
    source = json.loads((ASSETS / "glide" / f"{language}.json").read_text(encoding="utf-8"))
    ranks = {}
    for text, rank in source.items():
        word = text.lower().replace("’", "'")
        if 1 <= len(word) <= 32 and all(letter in codes for letter in word):
            ranks[word] = max(rank, ranks.get(word, 0))
    for line in (ASSETS / "typing" / "never-suggest.tsv").read_text(encoding="utf-8").splitlines():
        if not line.startswith(f"{language}\tswear\t"):
            continue
        _, _, word, *rank = line.split("\t")
        if 1 <= len(word) <= 32 and all(letter in codes for letter in word):
            if rank:
                ranks[word] = max(ranks.get(word, 0), int(rank[0]))
            else:
                ranks.setdefault(word, 177)
    # Dictate encodes log-frequency ranks. Restore relative weights before quantising costs.
    weights = {word: math.exp((rank - 255) * .145) for word, rank in ranks.items()}
    encoded = {word: bytes(codes[letter] for letter in word) for word in weights}
    words = sorted(encoded, key=encoded.get)
    indices = {word: index for index, word in enumerate(words)}
    counts = defaultdict(Counter)
    for line in (ASSETS / "glide" / f"{language}_bigrams.txt").read_text(encoding="utf-8").splitlines():
        pair, _, count = line.rpartition("\t")
        first, _, second = pair.partition(" ")
        if first in indices and second in indices:
            counts[first][indices[second]] += int(count)

    starts, rows, seconds = [0], [0], []
    spellings = bytearray()
    pair_costs = bytearray()
    for word in words:
        spellings.extend(encoded[word])
        starts.append(len(spellings))
        following = counts.get(word, {})
        total = sum(following.values())
        denominator = total + max(40, total * .55)
        for second, count in sorted(following.items()):
            seconds.append(second)
            pair_costs.append(cost(count / denominator))
        rows.append(len(seconds))

    result = bytearray(b"HXTM")
    result.extend(struct.pack("<IIIII4s", 3, len(words), len(seconds), len(spellings),
                              len(codes), language.encode("ascii")))
    for letter, index in codes.items():
        base = codes["е"] if letter == "ё" else index
        result.extend(struct.pack("<IBBH", ord(letter), index, base, 0))
    result.extend(struct.pack(f"<{len(starts)}I", *starts))
    total = sum(weights.values())
    result.extend(cost(weights[word] / total) for word in words)
    result.extend(b"\0" * (-len(result) % 4))
    result.extend(struct.pack(f"<{len(rows)}I", *rows))
    result.extend(struct.pack(f"<{len(seconds)}I", *seconds))
    result.extend(pair_costs)
    result.extend(spellings)
    (ASSETS / "typing" / f"{language}.hextm").write_bytes(result)
    print(f"{language}: {len(words):,} words, {len(seconds):,} pairs")


if __name__ == "__main__":
    for language, alphabet in ALPHABETS.items():
        model(language, alphabet)
