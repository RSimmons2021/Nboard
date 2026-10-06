"""Tunes phrase memory: how soon and how strongly remembered continuations are suggested.

Replicates PhraseMemory (record/suggestions) and PredictionRanker's personal terms on top of a
blend dump of habit_cases.tsv (make_habit_cases.py), using habit_history.txt as the user's
history, and reports habit / general top-1 and top-3 for each setting.
usage: fit_phrases.py blend-dump.tsv habit_cases.tsv habit_history.txt english_50k.txt english_bigrams.txt
"""
import math, re, sys
from collections import Counter, defaultdict

dump_path, cases_path, history_path, dict_path, bigram_path = sys.argv[1:6]
DAY = 86_400_000
NOW = 1_800_000_000_000
word_re = re.compile(r"[a-z]+(?:'[a-z]+)*")
token_re = re.compile(r"[A-Za-z']+")

freq = {l.split()[0]: int(l.split()[1]) for l in open(dict_path, encoding='utf-8') if len(l.split()) == 2}
dict_bigrams = defaultdict(dict)
for line in open(bigram_path, encoding='utf-8'):
    p = line.split()
    if len(p) == 3:
        dict_bigrams[p[0]][p[1]] = int(p[2])


def sentence_tokens(text):
    last = max((m.end() for m in re.finditer(r"[.!?\n\r]", text)), default=0)
    return [t for t in word_re.findall(text[last:].lower().replace('’', "'")) if 1 <= len(t) <= 24]


class Phrases:
    """PhraseMemory with tunable minimum context and minimum count."""
    def __init__(self, min_context, capacity=2048):
        self.min_context, self.capacity = min_context, capacity
        self.index = defaultdict(dict)  # context -> word -> [count, last]

    def record(self, context, word, now):
        previous = sentence_tokens(context)[-5:]
        word = word.lower().replace('’', "'")
        if len(previous) < self.min_context or not re.fullmatch(r"[a-z]+(?:'[a-z]+)*", word) or len(word) > 24:
            return
        for length in range(self.min_context, len(previous) + 1):
            entry = self.index['|'.join(previous[-length:])].setdefault(word, [0, now])
            entry[0] = min(entry[0] + 1, 1000); entry[1] = now
        size = sum(len(v) for v in self.index.values())
        if size > self.capacity:
            ranked = sorted(((c, w, e) for c, ws in self.index.items() for w, e in ws.items()),
                            key=lambda x: -math.log(1 + x[2][0]) / (1 + (now - x[2][1]) / (90 * DAY)))[:self.capacity]
            self.index = defaultdict(dict)
            for c, w, e in ranked:
                self.index[c][w] = e

    def suggestions(self, context, min_count, now):
        tokens = sentence_tokens(context)[-5:]
        reliable = [L for L in range(4, len(tokens) + 1)
                    if any(e[0] >= 3 for e in self.index.get('|'.join(tokens[-L:]), {}).values())]
        start = max(reliable) if reliable else max(self.min_context, 2 if self.min_context >= 2 else 1)
        result = {}
        for length in range(start, len(tokens) + 1):
            for word, (count, last) in self.index.get('|'.join(tokens[-length:]), {}).items():
                if count < min_count:
                    continue
                age = max(now - last, 0) / DAY
                score = math.log(1 + count) * 2 + (length - 1) * .8 + 1.2 / (1 + age / 30)
                result[word] = max(result.get(word, 0), score)
        return result


history = [l.rstrip('\n') for l in open(history_path, encoding='utf-8')]
words, bigrams, trigrams = Counter(), Counter(), Counter()
for message in history:
    for sentence in re.split(r"[.!?\n]", message.lower()):
        tokens = word_re.findall(sentence)
        for i, w in enumerate(tokens):
            if len(w) < 2:
                continue
            words[w] += 1
            if i >= 1: bigrams[f"{tokens[i-1]}|{w}"] += 1
            if i >= 2: trigrams[f"{tokens[i-2]}|{tokens[i-1]}|{w}"] += 1
words = Counter(dict(words.most_common(2600)))
bigrams = Counter(dict(bigrams.most_common(5200)))
trigrams = Counter(dict(trigrams.most_common(6800)))
import os
CAPACITIES = [int(c) for c in os.environ.get('CAPACITIES', '2048').split(',')]
memories = {}
for min_context, capacity in [(c, cap) for c in ((2,) if os.environ.get('CAPACITY_SWEEP') else (1, 2)) for cap in CAPACITIES]:
    memory = Phrases(min_context, capacity)
    start = NOW - 60 * DAY
    for i, message in enumerate(history):
        for match in token_re.finditer(message):
            memory.record(message[:match.start()], match.group(0), start + i * (60 * DAY // len(history)))
    memories[(min_context, capacity)] = memory

cases = [l.rstrip('\n').split('\t') for l in open(cases_path, encoding='utf-8')]
rows = []
for line in open(dump_path, encoding='utf-8'):
    parts = line.rstrip('\n').split('\t')
    candidates = {}
    for item in parts[2:]:
        word, base, prior, likelihood = item.rsplit(':', 3)
        candidates[word] = (float(base), float(prior), None if likelihood == '-' else float(likelihood))
    rows.append((parts[0], parts[1], candidates))
assert len(rows) == 2 * len(cases)

NEXT = (0.0, 0.0, .7, 1.8)          # PredictionRanker.NEXT_WORD_PERSONAL
COMPLETE = (1.65, 1.8, 2.5, 3.0)    # PredictionRanker.COMPLETION_PERSONAL
BLEND = {False: (1.0, .5), True: (.8, 2.5)}  # LocalPredictionEngine keyboard-model blends


def evaluate(min_context, min_count, weight, completion, capacity=CAPACITIES[0]):
    memory = memories[(min_context, capacity)]
    stats = defaultdict(lambda: [0, 0, 0])
    a, r, b, t = COMPLETE if completion else NEXT
    drop, lw = BLEND[completion]
    for i, (expected, prefix, candidates) in enumerate(rows):
        if bool(prefix) != completion:
            continue
        context, _, kind = cases[i // 2]
        tokens = sentence_tokens(context)
        one = tokens[-1] if tokens else ''
        two = tokens[-2] if len(tokens) > 1 else ''
        phrases = {w: s * weight for w, s in memory.suggestions(context, min_count, NOW).items()} if weight else {}
        pool = dict(candidates)
        for w in phrases:
            if w not in pool and w.startswith(prefix) and w != prefix:
                prior = math.log(1 + freq.get(w, 1)) * .32 + math.log(1 + dict_bigrams[one].get(w, 0)) * .48
                pool[w] = (prior, prior, None)
        scored = [v[2] for v in pool.values() if v[2] is not None]
        floor = max(min(scored), -25.0) if scored else -16.0

        def score(item):
            w, (base, prior, ll) = item
            own = freq.get(w, 0) < 2000
            personal = ((a * math.log(1 + words.get(w, 0)) + r * .5 if own and w in words else 0.0) +
                        b * math.log(1 + bigrams.get(f"{one}|{w}", 0)) + t * math.log(1 + trigrams.get(f"{two}|{one}|{w}", 0)))
            return base + personal + phrases.get(w, 0.0) - prior * drop + (ll if ll is not None else floor) * lw
        top = [w for w, _ in sorted(pool.items(), key=lambda it: -score(it))[:3]]
        for key in (kind, 'all'):
            s = stats[key]; s[0] += 1; s[1] += bool(top) and top[0] == expected; s[2] += expected in top
    return {k: (100 * v[1] / v[0], 100 * v[2] / v[0]) for k, v in stats.items()}


if os.environ.get('CAPACITY_SWEEP'):
    for completion in (False, True):
        print('\ncompletion (1 letter)' if completion else '\nnext word')
        for capacity in CAPACITIES:
            for min_count in (2, 1):
                r = evaluate(2, min_count, 1.0, completion, capacity)
                print(f"  capacity={capacity} count>={min_count}: habit top1={r['habit'][0]:.1f}% top3={r['habit'][1]:.1f}% | "
                      f"general top1={r['general'][0]:.1f}% top3={r['general'][1]:.1f}%")
    sys.exit()
for completion in (False, True):
    print('\ncompletion (1 letter)' if completion else '\nnext word')
    results = []
    for min_context in (2, 1):
        for min_count in (2, 1):
            for weight in (0, 1.0, 2.0, 3.0, 5.0):
                if weight == 0 and (min_context, min_count) != (2, 2):
                    continue
                r = evaluate(min_context, min_count, weight, completion)
                results.append((min_context, min_count, weight, r))
                mark = '  <- current' if (min_context, min_count, weight) == (2, 2, 1.0) else ''
                print(f"  context>={min_context} count>={min_count} weight={weight}: habit top1={r['habit'][0]:.1f}% top3={r['habit'][1]:.1f}% | "
                      f"general top1={r['general'][0]:.1f}% top3={r['general'][1]:.1f}%{mark}")
