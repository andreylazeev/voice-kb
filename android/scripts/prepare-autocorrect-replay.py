#!/usr/bin/env python3
"""Prepare independent word spelling replay cases; source corpora are not bundled."""
import argparse
import csv
import difflib
import hashlib
from itertools import islice
import json
import random
import re
import struct
from pathlib import Path


ALPHABETS = {"ru": "а-яё", "en": "a-z"}


def spelling_pairs(path):
    corrected = ""
    for line in path.read_text(encoding="latin-1").splitlines():
        if line.startswith("$"):
            corrected = line[1:].strip()
        else:
            yield line.split()[0] if line.split() else "", corrected, ""


def russian_pairs(path):
    with path.open(encoding="utf-8-sig") as stream:
        for row in csv.DictReader(stream):
            yield row["incorrect"], row["correct"], ""


def contextual_pairs(path):
    for line in path.read_text(encoding="utf-8").splitlines():
        row = json.loads(line)
        before = re.findall(r"[а-яё]+", row["source"].lower())
        after = re.findall(r"[а-яё]+", row["correction"].lower())
        for change, a, b, c, d in difflib.SequenceMatcher(a=before, b=after, autojunk=False).get_opcodes():
            if change == "replace" and b - a == d - c:
                for i in range(b - a):
                    yield before[a + i], after[c + i], before[a + i - 1] if a + i else ""


def common_words(path, alphabet):
    data = path.read_bytes()
    magic, version, words, _, spellings, letters = struct.unpack_from("<4s5I", data)
    if (magic, version) != (b"HXTM", 3):
        raise ValueError(f"Unsupported typing model: {path}")
    keys = {}
    for i in range(letters):
        letter, code = struct.unpack_from("<IB", data, 28 + 8 * i)
        keys[code] = chr(letter)
    starts = struct.unpack_from(f"<{words + 1}I", data, 28 + 8 * letters)
    freq = 28 + 8 * letters + 4 * (words + 1)
    blob = len(data) - spellings
    accepted = re.compile(f"[{alphabet}]{{4,12}}")
    for index in sorted(range(words), key=lambda i: (data[freq + i], i)):
        word = "".join(keys[code] for code in data[blob + starts[index]:blob + starts[index + 1]])
        if accepted.fullmatch(word):
            yield word


def keyboard_neighbours(language):
    rows = dict(en=("qwertyuiop", "asdfghjkl", "zxcvbnm"),
                ru=("йцукенгшщзхъ", "фывапролджэ", "ячсмитьбю"))[language]
    columns = max(len(rows[0]), len(rows[1]))
    centres = {c: ((columns - len(row)) / 2 + x, y * 1.44)
               for y, row in enumerate(rows) for x, c in enumerate(row)}
    return {c: [letter for letter, (a, b) in centres.items()
                if c != letter and (x - a) ** 2 + (y - b) ** 2 < 2.5]
            for c, (x, y) in centres.items()}


def keyboard_pairs(language, model):
    neighbours = keyboard_neighbours(language)
    rng = random.Random(25117)
    words = list(islice(common_words(model, ALPHABETS[language]), 6000))
    cases = {key: [] for key in ("neighbour", "omitted", "repeat", "transpose")}
    for word in rng.sample(words, min(len(words), 2500)):
        for case in cases:
            i = rng.randrange(len(word) - 1)
            c = word[i]
            if case == "neighbour":
                if c not in neighbours:
                    continue
                raw = word[:i] + rng.choice(neighbours[c]) + word[i + 1:]
            elif case == "omitted":
                raw = word[:i] + word[i + 1:]
            elif case == "repeat":
                raw = word[:i] + c + word[i:]
            else:
                raw = word[:i] + word[i + 1] + c + word[i + 2:]
            cases[case].append((raw, word, ""))
    return cases


def double_neighbour_pairs(model):
    neighbours = keyboard_neighbours("ru")
    rng = random.Random(261122)
    words = [word for word in islice(common_words(model, ALPHABETS["ru"]), 12_000)
             if len(word) >= 6 and all(c in neighbours for c in word)]
    for word in rng.sample(words, min(len(words), 2500)):
        typed = list(word)
        for index in rng.sample(range(len(word)), 2):
            typed[index] = rng.choice(neighbours[word[index]])
        yield "".join(typed), word, ""


def add_cases(writer, language, corpus, candidates):
    accepted = re.compile(f"[{ALPHABETS[language]}]{{2,32}}")
    expected = re.compile(f"[{ALPHABETS[language]}' -]{{2,40}}")
    previous_word = re.compile(f"[{ALPHABETS[language]}]{{1,32}}")
    cases = {}
    for raw, fixed, previous in candidates:
        raw, fixed = raw.strip().lower(), fixed.strip().lower()
        if raw == fixed or not accepted.fullmatch(raw) or not expected.fullmatch(fixed):
            continue
        previous = previous if previous_word.fullmatch(previous) else ""
        cases.setdefault((raw, previous), set()).add(fixed)
    clean = set()
    for (raw, previous), answers in sorted(cases.items()):
        if len(answers) != 1:
            continue
        fixed, = answers
        writer.writerow((language, corpus, "typo", raw, fixed, previous))
        if accepted.fullmatch(fixed):
            clean.add((fixed, previous))
    for word, previous in sorted(clean):
        writer.writerow((language, corpus, "clean", word, word, previous))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--birkbeck", type=Path, required=True)
    parser.add_argument("--wikipedia", type=Path, required=True)
    parser.add_argument("--russian-pairs", type=Path, required=True)
    parser.add_argument("--ruspell", type=Path, required=True)
    parser.add_argument("--russian-multidomain", type=Path)
    parser.add_argument("--keyboard-models", type=Path, help="Directory of fixed baseline .hextm files for synthetic mobile typos")
    parser.add_argument("--double-neighbour", action="store_true", help="Also simulate two Russian neighbouring-key substitutions")
    args = parser.parse_args()
    if args.double_neighbour and not args.keyboard_models:
        parser.error("--double-neighbour requires --keyboard-models")
    with args.output.open("w", encoding="utf-8", newline="") as output:
        writer = csv.writer(output, delimiter="\t", lineterminator="\n")
        add_cases(writer, "en", "birkbeck", spelling_pairs(args.birkbeck))
        add_cases(writer, "en", "wikipedia", spelling_pairs(args.wikipedia))
        add_cases(writer, "ru", "mendeley", russian_pairs(args.russian_pairs))
        add_cases(writer, "ru", "ruspell", contextual_pairs(args.ruspell))
        if args.russian_multidomain:
            add_cases(writer, "ru", "multidomain", contextual_pairs(args.russian_multidomain))
        if args.keyboard_models:
            for language in ALPHABETS:
                model = args.keyboard_models / f"{language}.hextm"
                for case, pairs in keyboard_pairs(language, model).items():
                    add_cases(writer, language, f"keyboard_{case}", pairs)
            if args.double_neighbour:
                add_cases(writer, "ru", "keyboard_double_neighbour", double_neighbour_pairs(args.keyboard_models / "ru.hextm"))
    print(hashlib.sha256(args.output.read_bytes()).hexdigest(), args.output)


if __name__ == "__main__":
    main()
