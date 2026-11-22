#!/usr/bin/env python3
"""Build the Russian vocabulary supplement from pinned GitHub word lists.

Input: Koziev/NLP_Datasets WordformFrequencies term2freq.dat (UTF-8)
and danakt/russian-words russian.txt (Windows-1251).
"""
import argparse
from collections import Counter
import json
import math
from pathlib import Path
import re

ASSETS = Path(__file__).resolve().parents[1] / "app/src/main/assets"
RUSSIAN = re.compile(r"[а-яё]{2,32}\Z")
RESTRICTED_STEM = re.compile(r"пизд|бляд|хуяр|хуяч|хуйн|пид[ао]р|долбо[её]б|(?:^|за|про|отъ|съ|въ)[её]б(?:а|л|н|и)")

# Recent names and conversational spellings are underrepresented in older text.
# Keep these ranks below common ordinary words.
MODERN = {
    207: "Яндекс Яндекса Яндексу Яндексом Яндексе Телеграм Телеграме Телеграма Телеграму",
    198: "Гугл Гугле Гугла Гуглом Самсунг Самсунга Самсунге Авито Сбер Сбера Сбере Ватсап Ватсапе",
    193: "Андроид Андроиде Андроида Ютуб Ютубе Ютуба Вайбер Вайбере Дискорд Дискорде Айфон Айфоне Айфона Айфоном",
    195: "клава клаве клаву клавой клавы свайп свайпа свайпом свайпе свайпы свайпов свайпать свайпаю свайпаешь свайпал",
    183: "свайпнуть свайпнул свайпнула свайпни свайпами андроидный видос видосы видоса скрин скрина скрины скриншот мем мемы мемов",
}


def candidate_rows(counts, dictionary, baseline, blocked, minimum, limit):
    peak = max(counts.values())
    ranks = {}
    for word, count in counts.most_common():
        if count < minimum or len(ranks) >= limit:
            break
        if word in baseline or word in blocked or RESTRICTED_STEM.search(word):
            continue
        if count < 400 and word not in dictionary:
            continue
        ranks[word] = max(125, min(225, round(255 + math.log(count / peak) / .145)))
    for rank, words in MODERN.items():
        for display in words.split():
            ranks.pop(display.lower(), None)
            ranks[display] = rank
    return ranks


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("frequencies", type=Path)
    parser.add_argument("dictionary", type=Path)
    parser.add_argument("--minimum", type=int, default=100)
    parser.add_argument("--limit", type=int, default=48_000)
    parser.add_argument("--output", type=Path, default=ASSETS / "glide/ru-extra.tsv")
    args = parser.parse_args()

    counts = Counter()
    with args.frequencies.open(encoding="utf-8-sig") as source:
        for line in source:
            fields = line.rstrip("\r\n").split("\t")
            if len(fields) != 3 or not fields[2].isdigit():
                continue
            word = fields[0].lower()
            if RUSSIAN.fullmatch(word):
                counts[word] += int(fields[2])
    dictionary = set(args.dictionary.read_text(encoding="cp1251").lower().splitlines())
    baseline = {word.lower() for word in json.loads((ASSETS / "glide/ru.json").read_text(encoding="utf-8"))}
    blocked = {
        fields[2]
        for line in (ASSETS / "typing/never-suggest.tsv").read_text(encoding="utf-8").splitlines()
        if not line.startswith("#") and len(fields := line.split("\t")) >= 3 and fields[0] in {"*", "ru"}
    }
    ranks = candidate_rows(counts, dictionary, baseline, blocked, args.minimum, args.limit)
    with args.output.open("w", encoding="utf-8", newline="\n") as output:
        for word in sorted(ranks, key=str.lower):
            output.write(f"{word}\t{ranks[word]}\n")
    print(f"Wrote {len(ranks):,} entries to {args.output}")


if __name__ == "__main__":
    main()
