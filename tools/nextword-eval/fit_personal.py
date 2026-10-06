"""How learned history changes next-word ranking, and which weights use it best.

Simulates a user's typing history from conversational messages that are NOT in the eval
cases, builds the app's learned stores from it (word counts, word pairs, word triples,
last-used times, capped like the app), adds PredictionRanker's personal terms to a blend
dump (PredictionBlendDumpDeviceTest on nextword_dev.tsv) and sweeps their weights.

usage: fit_personal.py blend-dump.tsv nextword_dev.tsv english_50k.txt english_bigrams.txt \
                       SMSSpamCollection dialogues_test.txt [history_messages]
"""
import math, os, random, re, sys
from collections import Counter
from itertools import product

dump_path, cases_path, dict_path, bigram_path, sms_path, dd_path = sys.argv[1:7]
history_size = int(sys.argv[7]) if len(sys.argv) > 7 else 3000
MAX_WORDS, MAX_BIGRAMS, MAX_TRIGRAMS = 2600, 5200, 6800
word_re = re.compile(r"[a-z]+(?:'[a-z]+)*")

freq = {l.split()[0]: int(l.split()[1]) for l in open(dict_path, encoding='utf-8') if len(l.split()) == 2}
dict_bigrams = {}
for line in open(bigram_path, encoding='utf-8'):
    p = line.split()
    if len(p) == 3:
        dict_bigrams.setdefault(p[0], {})[p[1]] = int(p[2])


def sentence_tokens(text):
    """PhraseMemory.sentenceTokens: words of the current sentence only."""
    last = max((m.end() for m in re.finditer(r"[.!?\n\r]", text)), default=0)
    return word_re.findall(text[last:].lower().replace('’', "'"))


cases = [l.rstrip('\n').split('\t') for l in open(cases_path, encoding='utf-8')]
rows = []
for line in open(dump_path, encoding='utf-8'):
    parts = line.rstrip('\n').split('\t')
    candidates = {}
    for item in parts[2:]:
        word, base, prior, likelihood = item.rsplit(':', 3)
        candidates[word] = (float(base), float(prior), None if likelihood == '-' else float(likelihood))
    rows.append((parts[0], parts[1], candidates))
assert len(rows) == 2 * len(cases), (len(rows), len(cases))

# History: other people's messages, excluding every message an eval case came from.
used = {c[0] + c[1] for c in cases}
def detok(t):
    t = re.sub(r" ([.,!?;:%)])", r"\1", t.strip())
    return re.sub(r" ?(['’]) ?(s|t|re|ve|ll|d|m)\b", r"\1\2", t)
messages = [l.split('\t', 1)[1].strip() for l in open(sms_path, encoding='utf-8', errors='ignore') if l.startswith('ham\t')]
messages += [detok(t) for d in open(dd_path, encoding='utf-8') for t in d.split('__eou__') if t.strip()]
by_start = {}
for c in cases:
    by_start.setdefault(c[0][:16], []).append(c[0])
messages = [m for m in messages if not any(m.startswith(ctx) for ctx in by_start.get(m[:16], []))]
rng = random.Random(7)
history = rng.sample(messages, min(history_size, len(messages)))
if os.environ.get('HISTORY_OUT'):  # for ConversationalPredictionDeviceTest's history mode
    with open(os.environ['HISTORY_OUT'], 'w', encoding='utf-8') as out:
        out.writelines(m.replace('\n', ' ') + '\n' for m in history)

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
words = Counter(dict(words.most_common(MAX_WORDS)))
bigrams = Counter(dict(bigrams.most_common(MAX_BIGRAMS)))
trigrams = Counter(dict(trigrams.most_common(MAX_TRIGRAMS)))
age = {w: rng.uniform(0, 60) for w in words}  # days since last use
print(f"history: {len(history)} messages, {len(words)} words, top: {', '.join(f'{w}:{c}' for w, c in words.most_common(8))}")


import os
GATE = int(os.environ.get('VOCAB_GATE', '0'))  # >0: frequency/recency bonus only for words rarer than this


def personal(word, one, two, a, r, b, t):
    own = GATE == 0 or freq.get(word, 0) < GATE
    return ((a * math.log(1 + words.get(word, 0)) if own else 0.0) +
            (r * 1.0 / (1 + age[word] / 14) if word in age and own else 0.0) +
            b * math.log(1 + bigrams.get(f"{one}|{word}", 0)) +
            t * math.log(1 + trigrams.get(f"{two}|{one}|{word}", 0)))


def evaluate(weights, completion, blend, use_history=True):
    a, r, b, t = weights
    drop, lw = blend
    top1 = top3 = n = 0
    firsts = Counter()
    # Learned words with no pair/triple evidence score the same in every next-word case
    # (prior fully dropped): rank them once per weight setting.
    general = sorted(words, key=lambda w: -(a * math.log(1 + words[w]) + r / (1 + age[w] / 14)))[:6] if use_history else []
    for i, (expected, prefix, candidates) in enumerate(rows):
        if bool(prefix) != completion:
            continue
        n += 1
        tokens = sentence_tokens(cases[i // 2][0])
        one = tokens[-1] if tokens else ''
        two = tokens[-2] if len(tokens) > 1 else ''
        pool = dict(candidates)
        if use_history:  # the app also offers learned words that match the prefix
            extra = context_words.get((one, two), set()) | set(general if not prefix else by_letter.get(prefix, ()))
            for w in extra:
                if w not in pool and w.startswith(prefix) and w != prefix:
                    prior = math.log(1 + freq.get(w, 1)) * .32 + math.log(1 + dict_bigrams.get(one, {}).get(w, 0)) * .48
                    pool[w] = (prior - (len(w) - len(prefix)) * .025 if prefix else prior, prior, None)
        scored = [v[2] for v in pool.values() if v[2] is not None]
        floor = max(min(scored), -25.0) if scored else -16.0
        def score(item):
            w, (base, prior, ll) = item
            extra = personal(w, one, two, a, r, b, t) if use_history else 0.0
            return base + extra - prior * drop + (ll if ll is not None else floor) * lw
        top = [w for w, _ in sorted(pool.items(), key=lambda it: -score(it))[:3]]
        top1 += bool(top) and top[0] == expected
        top3 += expected in top
        if top: firsts[top[0]] += 1
    return 100 * top1 / n, 100 * top3 / n, len(firsts), firsts.most_common(5)


# Learned words linked to a context by a remembered pair or triple.
context_words = {}
for key in bigrams:
    one, w = key.split('|')
    for two in [None]:
        context_words.setdefault(('*', one), set()).add(w)
for key in trigrams:
    two, one, w = key.split('|')
    context_words.setdefault((one, two), set()).add(w)
_pairs = context_words
context_words = type('Lookup', (), {'get': staticmethod(lambda k, d=set(): _pairs.get(('*', k[0]), set()) | _pairs.get(k, set()))})
by_letter = {}
for w in words:
    by_letter.setdefault(w[0], []).append(w)


CURRENT = (1.65, 1.8, 1.4, 1.8)
for completion, blend in ((False, (1.0, .5)), (True, (.8, 2.5))):
    label = 'completion (1 letter)' if completion else 'next word'
    base = evaluate(CURRENT, completion, blend, use_history=False)
    now = evaluate(CURRENT, completion, blend)
    grid = [(w, evaluate(w, completion, blend)) for w in (
        [tuple(map(float, x.split(','))) for x in os.environ['GRID'].split(';')] if 'GRID' in os.environ else
        product([0, .25, .5, 1.0, 1.65], [0, .5, 1.8], [.7, 1.4, 2.5], [1.8, 3.0]))]
    best_w, best = max(grid, key=lambda g: (g[1][0] + g[1][1], g[1][0]))
    print(f"\n{label}")
    print(f"  no history:           top1={base[0]:.1f}% top3={base[1]:.1f}% distinct top-1 words={base[2]} most common={base[3]}")
    print(f"  history, current wts: top1={now[0]:.1f}% top3={now[1]:.1f}% distinct top-1 words={now[2]} most common={now[3]}")
    print(f"  history, best {best_w}: top1={best[0]:.1f}% top3={best[1]:.1f}% distinct top-1 words={best[2]} most common={best[3]}")
