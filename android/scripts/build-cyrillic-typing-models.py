#!/usr/bin/env python3
"""Convert the bundled glide vocabulary and adjacent-word counts to HXTM v3."""

import argparse
import json
import math
import struct
from collections import Counter, defaultdict
from itertools import chain
from pathlib import Path


ASSETS = Path(__file__).resolve().parents[1] / "app/src/main/assets"
ALPHABETS = {
    "ru": "абвгдеёжзийклмнопрстуфхцчшщъыьэюя",
    "uk": "абвгґдеєжзиіїйклмнопрстуфхцчшщьюя'",
}


def cost(probability):
    return min(255, round(-8 * math.log(min(1.0, probability))))


def model(language, alphabet, typing_extra=None):
    codes = {letter: number for number, letter in enumerate(sorted(alphabet), 1)}
    source = json.loads((ASSETS / "glide" / f"{language}.json").read_text(encoding="utf-8"))
    ranks = {}
    for text, rank in source.items():
        word = text.lower().replace("’", "'")
        if 1 <= len(word) <= 32 and all(letter in codes for letter in word):
            ranks[word] = max(rank, ranks.get(word, 0))
    if language == "ru":
        names = set()
        for row in (ASSETS / "glide" / "ru-extra.tsv").read_text(encoding="utf-8").splitlines():
            spelling, value = row.split("\t")
            word = spelling.lower()
            ranks[word] = max(ranks.get(word, 0), int(value))
            if spelling != word:
                names.add(word)
        (ASSETS / "typing" / "ru.capitals").write_text(
            "".join(f"{word}\t100\n" for word in sorted(names)), encoding="utf-8"
        )
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
    counts = defaultdict(Counter)
    for line in (ASSETS / "glide" / f"{language}_bigrams.txt").read_text(encoding="utf-8").splitlines():
        pair, _, count = line.rpartition("\t")
        first, _, second = pair.partition(" ")
        if first in weights and second in weights:
            counts[first][second] += int(count)
    total = sum(weights.values())
    write_model(ASSETS / "typing" / f"{language}.hextm", language, codes, weights, total, counts)
    if language == "ru" and typing_extra:
        extra = {}
        with typing_extra.open(encoding="utf-8") as source:
            for row in source:
                word, rank = row.rstrip("\n").split("\t")
                if word not in weights:
                    extra[word] = math.exp((int(rank) - 255) * .145)
        write_model(ASSETS / "typing" / "ru.correction.hextm", language, codes, extra, total, {})
        with (ASSETS / "typing" / "ru.valid").open(encoding="utf-8") as source:
            valid = (line.rstrip("\n").split("\t", 1)[0] for line in source)
            write_spelling_patterns(codes, chain(weights, extra, valid))


def write_spelling_patterns(codes, words):
    radix = len(codes) + 1
    triple_size = radix ** 3
    triple_counts = [0] * triple_size
    quadruples = bytearray((radix ** 4 + 7) // 8)
    bases = {**codes, "ё": codes["е"]}
    for word in words:
        letters = [0, *(bases[letter] for letter in word), 0]
        for end in range(2, len(letters)):
            triple = (letters[end - 2] * radix + letters[end - 1]) * radix + letters[end]
            triple_counts[triple] += 1
            if end >= 3:
                quad = letters[end - 3] * triple_size + triple
                quadruples[quad // 8] |= 1 << (quad % 8)
    triples = bytes(round(4 * math.log2(1 + count)) for count in triple_counts)
    (ASSETS / "typing" / "ru.patterns").write_bytes(triples + quadruples)


def write_model(path, language, codes, weights, total, counts):
    if len(weights) > 250_000:
        raise ValueError(f"{path.name} has {len(weights):,} words, above the HXTM reader's limit")
    encoded = {word: bytes(codes[letter] for letter in word) for word in weights}
    words = sorted(encoded, key=encoded.get)
    indices = {word: index for index, word in enumerate(words)}

    starts, rows, seconds = [0], [0], []
    spellings = bytearray()
    pair_costs = bytearray()
    for word in words:
        spellings.extend(encoded[word])
        starts.append(len(spellings))
        following = counts.get(word, {})
        following_total = sum(following.values())
        denominator = following_total + max(40, following_total * .55)
        for second, count in sorted(following.items(), key=lambda pair: indices[pair[0]]):
            seconds.append(indices[second])
            pair_costs.append(cost(count / denominator))
        rows.append(len(seconds))

    result = bytearray(b"HXTM")
    result.extend(struct.pack("<IIIII4s", 3, len(words), len(seconds), len(spellings),
                              len(codes), language.encode("ascii")))
    for letter, index in codes.items():
        base = codes["е"] if letter == "ё" else index
        result.extend(struct.pack("<IBBH", ord(letter), index, base, 0))
    result.extend(struct.pack(f"<{len(starts)}I", *starts))
    result.extend(cost(weights[word] / total) for word in words)
    result.extend(b"\0" * (-len(result) % 4))
    result.extend(struct.pack(f"<{len(rows)}I", *rows))
    result.extend(struct.pack(f"<{len(seconds)}I", *seconds))
    result.extend(pair_costs)
    result.extend(spellings)
    path.write_bytes(result)
    print(f"{path.name}: {len(words):,} words, {len(seconds):,} pairs")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Build HXTM tap-typing models for Cyrillic layouts")
    parser.add_argument("--russian-typing-extra", type=Path, help="Ranked Russian words for tap autocorrection")
    args = parser.parse_args()
    for language, alphabet in ALPHABETS.items():
        model(language, alphabet, args.russian_typing_extra)
