"""Cases for phrase memory: does the keyboard finish what this person usually writes?

Messages sent more than once in the SMS corpus act as one user's habits. One copy of each
repeated message is held out as the message being typed; every other message is history.
"habit" cases come from those held-out copies; "general" cases from messages sent only once
(also held out of the history), to check that habits do not crowd out ordinary prediction.
usage: make_habit_cases.py english_50k.txt SMSSpamCollection out_dir
writes habit_cases.tsv (context, next word, kind) and habit_history.txt (one message per line)
"""
import random, re, sys
from collections import Counter

dict_path, sms_path, out_dir = sys.argv[1:4]
vocab = {l.split()[0] for l in open(dict_path, encoding='utf-8') if l.strip()}
word_re = re.compile(r"[A-Za-z']+")
messages = [l.split('\t', 1)[1].strip() for l in open(sms_path, encoding='utf-8', errors='ignore') if l.startswith('ham\t')]
rng = random.Random(11)
rng.shuffle(messages)

counts = Counter(messages)
held_out, history, seen = [], [], set()
for m in messages:
    if counts[m] > 1 and m not in seen:      # first copy of a repeated message: being typed now
        held_out.append(('habit', m)); seen.add(m)
    elif counts[m] == 1 and rng.random() < .12:
        held_out.append(('general', m))     # never sent before
    else:
        history.append(m)


def cases(kind, text):
    out = []
    for match in list(word_re.finditer(text))[1:]:
        word = match.group(0).lower().strip("'")
        context = text[:match.start()]
        if word in vocab and context.endswith(' ') and word not in {'s', 't', 're', 've', 'll', 'd', 'm'}:
            out.append((context.replace('\t', ' ').replace('\n', ' '), word, kind))
    return out


def sample(kind, limit, per_message=6):
    """At most a few positions per message, so long repeated messages do not dominate."""
    picked = []
    for k, m in held_out:
        if k == kind:
            found = cases(kind, m)
            picked += rng.sample(found, min(per_message, len(found)))
    rng.shuffle(picked)
    return picked[:limit]


habit = sample('habit', 600)
general = sample('general', 600)
selected = habit + general
with open(f"{out_dir}/habit_cases.tsv", 'w', encoding='utf-8') as out:
    out.writelines(f"{c}\t{w}\t{k}\n" for c, w, k in selected)
with open(f"{out_dir}/habit_history.txt", 'w', encoding='utf-8') as out:
    out.writelines(m.replace('\n', ' ') + '\n' for m in history)
print(f"{sum(1 for m in counts.values() if m > 1)} repeated messages; history {len(history)} messages; "
      f"cases: {len(habit)} habit + {len(general)} general")
