#!/usr/bin/env python3
"""Build the Russian swipe and tap vocabulary supplements from pinned word lists.

Input: Koziev/NLP_Datasets WordformFrequencies term2freq.dat (UTF-8)
and danakt/russian-words russian.txt (Windows-1251). The tap lists can also use
a Hunspell dictionary to verify attested word forms.
"""
import argparse
from collections import Counter
import json
import math
from pathlib import Path
import re
import subprocess

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


def typing_rows(counts, dictionary, existing, blocked, spellchecked):
    for word, count in sorted(counts.items()):
        if word in existing or word in blocked or RESTRICTED_STEM.search(word):
            continue
        if not ((count >= 20 and word in dictionary) or word in spellchecked):
            continue
        yield f"{word}\n"


def typing_candidates(counts, dictionary, spellchecked, existing, blocked):
    peak = max(counts.values())
    candidates = {}
    for word, count in counts.most_common():
        if count < 20 or len(candidates) == 110_000:
            break
        if word in existing or word in blocked or RESTRICTED_STEM.search(word):
            continue
        if word in spellchecked or (count >= 100 and word in dictionary):
            candidates[word] = round(255 + math.log(count / peak) / .145)
    return candidates


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("frequencies", type=Path)
    parser.add_argument("dictionary", type=Path)
    parser.add_argument("--minimum", type=int, default=100)
    parser.add_argument("--limit", type=int, default=48_000)
    parser.add_argument("--output", type=Path, default=ASSETS / "glide/ru-extra.tsv")
    parser.add_argument("--valid-output", type=Path, help="Write attested forms that the tap decoder should recognise")
    parser.add_argument("--typing-extra", type=Path, help="Write additional ranked forms for the tap model")
    parser.add_argument("--hunspell", type=Path, help="Hunspell dictionary path without the .dic/.aff suffix")
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
    existing = baseline | {word.lower() for word in ranks}
    spellchecked = set()
    if args.hunspell:
        # Only check forms observed in the independent frequency corpus.
        words = "".join(f"{word}\n" for word, count in counts.items() if count >= 5 and word not in existing)
        checked = subprocess.run(["hunspell", "-G", "-i", "UTF-8", "-d", str(args.hunspell.resolve())],
                                 input=words, capture_output=True, text=True, encoding="utf-8", check=True)
        spellchecked = set(checked.stdout.splitlines())
    if args.typing_extra:
        candidates = typing_candidates(counts, dictionary, spellchecked, existing, blocked)
        args.typing_extra.write_text("".join(f"{word}\t{rank}\n" for word, rank in sorted(candidates.items())),
                                     encoding="utf-8")
        existing.update(candidates)
        print(f"Wrote {len(candidates):,} correction candidates to {args.typing_extra}")
    if args.valid_output:
        rows = list(typing_rows(counts, dictionary, existing, blocked, spellchecked))
        args.valid_output.write_text("".join(rows), encoding="utf-8")
        print(f"Wrote {len(rows):,} recognised spellings to {args.valid_output}")


if __name__ == "__main__":
    main()
