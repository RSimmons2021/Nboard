#!/usr/bin/env python3
"""Builds the conversational word-triple table that helps Nboard rank common chat continuations
("would you" -> "like", "thank you" -> "so"), which the single-word pair counts miss.

Source: Google's Taskmaster-1 and Taskmaster-2 dialogues (CC BY 4.0,
https://github.com/google-research-datasets/Taskmaster). They are separate from the SMS and
DailyDialog text used for the next-word dev/test cases, so evaluation stays held out.
Download the JSON files (TM-1-2019/*-dialogs.json, TM-2-2020/data/*.json) into one folder:

    python3 build_chat_trigrams.py TASKMASTER_DIR ../../app/src/main/assets/dictionaries/english_50k.txt \\
        ../../app/src/main/assets/dictionaries/english_chat_trigrams.txt

Output lines: `first second next log_probability` (natural log of P(next | first second)).
Only two-word contexts seen at least MIN_CONTEXT times are kept, each with its MAX_NEXT most
frequent dictionary continuations seen at least MIN_COUNT times.
"""
import collections
import glob
import json
import math
import re
import sys

MIN_CONTEXT = 20
MIN_COUNT = 3
MAX_NEXT = 8
WORD = re.compile(r"[a-z]+(?:'[a-z]+)?")


def main(taskmaster_dir, dictionary_path, output_path):
    vocabulary = {line.split()[0] for line in open(dictionary_path, encoding="utf-8") if line.strip()}
    counts = collections.defaultdict(collections.Counter)
    for path in sorted(glob.glob(taskmaster_dir + "/*.json")):
        for dialogue in json.load(open(path, encoding="utf-8")):
            for utterance in dialogue.get("utterances", []):
                text = utterance.get("text", "").lower().replace("’", "'")
                for sentence in re.split(r"[.!?\n]+", text):
                    tokens = WORD.findall(sentence)
                    for i in range(2, len(tokens)):
                        counts[(tokens[i - 2], tokens[i - 1])][tokens[i]] += 1
    lines = []
    for (first, second), following in sorted(counts.items()):
        total = sum(following.values())
        if total < MIN_CONTEXT or first not in vocabulary or second not in vocabulary:
            continue
        for word, count in following.most_common(MAX_NEXT):
            if count >= MIN_COUNT and word in vocabulary:
                lines.append(f"{first} {second} {word} {math.log(count / total):.3f}")
    with open(output_path, "w", encoding="utf-8") as out:
        out.write("# Word triples from Google Taskmaster-1/2 dialogues, CC BY 4.0 "
                  "(https://github.com/google-research-datasets/Taskmaster)\n")
        out.write("\n".join(lines) + "\n")
    print(f"{len(lines)} triples -> {output_path}")


if __name__ == "__main__":
    main(*sys.argv[1:4])
