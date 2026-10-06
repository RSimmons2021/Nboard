"""Fits LocalPredictionEngine.combine() weights on a dump from PredictionBlendDumpDeviceTest.

score = base - prior * priorDrop + likelihood * weight   (unscored words use the lowest scored
likelihood, as combine() does). Reports top-1/top-3 for the current and the best weights,
separately for next-word (empty prefix) and completion (one typed letter).
usage: fit_blend.py blend-dump.tsv
"""
import sys
from itertools import product

rows = []
for line in open(sys.argv[1], encoding='utf-8'):
    parts = line.rstrip('\n').split('\t')
    expected, prefix = parts[0], parts[1]
    candidates = []
    for item in parts[2:]:
        word, base, prior, likelihood = item.rsplit(':', 3)
        candidates.append((word, float(base), float(prior), None if likelihood == '-' else float(likelihood)))
    rows.append((expected, prefix, candidates))


def evaluate(drop, weight, completion):
    top1 = top3 = n = 0
    for expected, prefix, candidates in rows:
        if bool(prefix) != completion:
            continue
        n += 1
        scored = [c[3] for c in candidates if c[3] is not None]
        floor = max(min(scored), -25.0) if scored else -16.0
        ranked = sorted(candidates, key=lambda c: -(c[1] - c[2] * drop + (c[3] if c[3] is not None else floor) * weight))
        top = []
        for c in ranked:
            if c[0] not in top:
                top.append(c[0])
            if len(top) == 3:
                break
        top1 += bool(top) and top[0] == expected
        top3 += expected in top
    return 100 * top1 / n, 100 * top3 / n, n


for completion in (False, True):
    label = 'completion (1 letter)' if completion else 'next word'
    current = evaluate(.65, 1.25, completion)
    grid = [(d, w, *evaluate(d, w, completion)) for d, w in product([0, .25, .5, .65, .8, .9, 1.0], [.5, .75, 1.0, 1.25, 1.75, 2.5, 4.0])]
    best = max(grid, key=lambda g: (g[2] + g[3], g[3]))  # top-1 and top-3 equally
    print(f"{label}: n={current[2]} current(.65,1.25) top1={current[0]:.1f}% top3={current[1]:.1f}% | "
          f"best drop={best[0]} weight={best[1]} top1={best[2]:.1f}% top3={best[3]:.1f}%")
