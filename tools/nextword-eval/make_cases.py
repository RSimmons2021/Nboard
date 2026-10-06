"""Builds next-word benchmark cases from conversational text.

Each case is the text of one message up to a word boundary (what the keyboard sees)
and the word typed next. Only dictionary words are targets, so every model can win.
Usage: make_cases.py <english_50k.txt> <SMSSpamCollection> <dialogues_test.txt> <out_dir>
"""
import random, re, sys

dict_path, sms_path, dd_path, out_dir = sys.argv[1:5]
vocab = {line.split()[0] for line in open(dict_path, encoding='utf-8') if line.strip()}
word_re = re.compile(r"[A-Za-z']+")


def detokenize(turn):
    """DailyDialog separates punctuation with spaces ("you ?", "it ' s")."""
    turn = re.sub(r" ([.,!?;:%)])", r"\1", turn.strip())
    turn = re.sub(r" ?(['’]) ?(s|t|re|ve|ll|d|m)\b", r"\1\2", turn)
    return re.sub(r"\( ", "(", turn)


def cases(messages, source, rng, limit):
    found = []
    for text in messages:
        for match in list(word_re.finditer(text))[1:]:
            word = match.group(0).lower().strip("'")
            context = text[:match.start()]
            if word not in vocab or not context.endswith(" ") or len(context) > 800:
                continue
            # Split contractions ("it ' s") are tokenisation artefacts, not typed words.
            if word in {'s', 't', 're', 've', 'll', 'd', 'm'} or context.rstrip().endswith(("'", "’", "´")):
                continue
            found.append((context, word, source))
    rng.shuffle(found)
    return found[:limit]


rng = random.Random(1234)
sms = [line.split('\t', 1)[1].strip() for line in open(sms_path, encoding='utf-8', errors='ignore') if line.startswith('ham\t')]
dd = [detokenize(turn) for dialogue in open(dd_path, encoding='utf-8') for turn in dialogue.split('__eou__') if turn.strip()]
all_cases = cases(sms, 'sms', rng, 1200) + cases(dd, 'dialog', rng, 1200)
rng.shuffle(all_cases)
half = len(all_cases) // 2
for name, part in (('dev', all_cases[:half]), ('test', all_cases[half:])):
    with open(f"{out_dir}/nextword_{name}.tsv", 'w', encoding='utf-8') as out:
        for context, word, source in part:
            out.write(f"{context.replace(chr(9), ' ').replace(chr(10), ' ')}\t{word}\t{source}\n")
print(len(sms), 'sms messages,', len(dd), 'dialog turns ->', len(all_cases), 'cases')
