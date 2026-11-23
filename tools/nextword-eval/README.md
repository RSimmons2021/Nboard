# Next-word evaluation

Measures next-word prediction on held-out conversational text, so model or ranking
changes are judged on numbers rather than impressions.

## 1. Build the cases

Sources (downloaded separately, not committed):
- UCI SMS Spam Collection, non-spam messages (CC BY 4.0)
- DailyDialog test split (`roskoN/dailydialog` on Hugging Face, `test.zip`)

```sh
python3 make_cases.py ../../app/src/main/assets/dictionaries/english_50k.txt \
    SMSSpamCollection dialogues_test.txt OUT_DIR
```

This writes `nextword_dev.tsv` and `nextword_test.tsv` (1,200 cases each:
`context<TAB>next word<TAB>source`). Tune on **dev** only; report **test**.

## 2. Compare models on a computer (model alone)

`nextword_eval.cpp` links the same llama.cpp revision as the app and scores any GGUF
with one tokenizer-independent procedure (dictionary-constrained beam search):

```sh
g++ -O2 -std=c++17 -o nextword_eval nextword_eval.cpp -I$LLAMA/include -I$LLAMA/ggml/include \
    $BUILD/src/libllama.a $BUILD/ggml/src/libggml.a $BUILD/ggml/src/libggml-cpu.a $BUILD/ggml/src/libggml-base.a -lpthread
./nextword_eval model.gguf english_50k.txt nextword_dev.tsv            # cased word-start models
./nextword_eval daen-xbu-q6_k.gguf english_50k.txt nextword_dev.tsv --lowercase --no-space-prefix
```

## 3. Measure the full pipeline on a phone (debug build)

```sh
adb shell "run-as com.nboard.ime sh -c 'mkdir -p files/eval && cat > files/eval/cases.tsv'" < nextword_test.tsv
# optional: evaluate another model without bundling it
adb shell "run-as com.nboard.ime sh -c 'mkdir -p files/models && cat > files/models/override.gguf'" < model.gguf
adb shell am instrument -w -e class com.nboard.ime.ConversationalPredictionDeviceTest \
    com.nboard.ime.test/androidx.test.runner.AndroidJUnitRunner
adb logcat -d -s NboardBenchmark
```

## 4. Refit the ranking blend

Copy `nextword_dev.tsv` to `files/eval/dev.tsv`, run `PredictionBlendDumpDeviceTest`,
pull `files/eval/blend-dump.tsv` and run `python3 fit_blend.py blend-dump.tsv`.
Put the chosen weights in `LocalPredictionEngine` (`*_NEXT_WORD` / `*_COMPLETION`).

## Results (2026-10-06, Nothing A024, 1,200 test cases, full pipeline)

| Model | Top-1 | Top-3 | Top-3 after 1 letter | Word boundary p50/p95 | Keystroke p50/p95 | Size |
|---|---|---|---|---|---|---|
| daen-xbu q6_k (66M, current) | 18.6% | 29.0% | 59.0% | 24 / 46 ms | 11 / 30 ms | 55 MB |
| SmolLM2-135M Q4_K_M | 21.0% | 32.5% | 61.8% | 74 / 105 ms | 10 / 48 ms | 105 MB |

Model alone on 1,200 dev cases (computer): current 17.8% / 32.3%, SmolLM2-135M
24.4% / 37.8%, SmolLM2-135M-Instruct 22.3% / 37.0%, Gemma-3-270M 25.8% / 41.6%
(2.3x slower than SmolLM2, 291 MB).

## Personal history (2026-10-06)

`fit_personal.py` simulates a long-time user (3,000 messages not in the eval cases) and
sweeps how learned history is weighted. With a flat bonus for often-typed words, next-word
suggestions collapsed onto a few words. Measured on the phone (1,200 test cases, current
model, `ConversationalPredictionDeviceTest` with `files/eval/history.txt`):

| Personal weighting | Top-1 | Top-3 | Distinct first suggestions |
|---|---|---|---|
| Flat often-typed/recency bonus (before) | 12.3% | 21.0% | 50 ("the" 312x) |
| Context only at word start; own-vocabulary bonus when completing (after) | 17.9% | 29.9% | 177 |
| No history (reference) | 18.6% | 29.0% | 221 |

The simulated history is other people's text, so it shows the harm of context-free bonuses
but understates the benefit of a user's own repeated pairs and phrases.

## Compare the visible strip across keyboards

Use [keyboard-comparison.tsv](keyboard-comparison.tsv) with English enabled and
predictions visible in a regular multiline text field. Use a scratch editor and
keep existing keyboard history. Type each context plus one trailing space using
the keyboard itself. Record the primary next-word suggestion and the visible
three after 500 ms. Compare case-insensitively. Ignore punctuation wrapped
around a suggestion, but retain internal apostrophes.

Record unfamiliar prompts once. For the habit prompts, first record a baseline.
Then type each full context and expected word followed by a space three times,
in a different order each round. Reopen the editor and record the habit prompts
again. Use the same procedure on all keyboards.

Report primary and top-three hits separately for each group. Compare habit
recall before and after practice. For Nboard, also record the first settled row
and the row at 500 ms to count changes. For Gboard and iOS, use the keyboard's
highlighted suggestion as primary. If the display doesn't show a clear primary,
record the three slots and report top-three only.

The small comparison checks behavior and provides a shared device protocol.
Use the full held-out corpus for engine decisions. A single plausible target
per prompt measures whether the intended word appeared. It cannot measure
whether every visible alternative was reasonable.

The optional Android debug experiment uses a locally supplied SmolLM2-135M
Q4_K_M GGUF. Upload it to `files/models/pause.gguf` in Nboard's private
storage and restart Nboard. The file is separate from `override.gguf`, which
replaces the first model. Run `ConversationalPredictionDeviceTest#comparePauseRefinement`
with `files/eval/nextword_test.tsv` to compare initial and paused top-three
accuracy, first-pass and pause inference latency, and how often the last slot
changes. For the habit replay, use `-e pauseCases habit_cases -e phraseCapacity 65536`
and provide `files/eval/habit_cases.tsv` and `files/eval/habit_cases_history.txt`.

### Nothing A024 comparison (2026-11-23)

| Top-three hits | Nboard debug build with pause model | Gboard 18.4 |
|---|---:|---:|
| Unfamiliar prompts | 6/12 | 9/12 |
| Habit prompts before practice | 0/8 | 0/8 |
| Habit prompts after three exposures | 7/8 | 4/8 |

Gboard prompts were entered with key taps. Nboard used the same text in the
scratch editor and three observations in a temporary phrase memory. Existing
Gboard history was retained. The practice methods and history differ, so the
habit scores illustrate behavior on these prompts. They do not establish a
general ranking. The Gboard row showed no clear primary slot, so the table
uses top-three hits.

Separate Nboard replay, with the installed SmolLM2-135M Q4_K_M:

| Replay group | Cases | First top three | Paused top three | Right slot changed |
|---|---:|---:|---:|---:|
| Held-out conversational text | 1,200 | 348 (29.0%) | 384 (32.0%) | 537 (44.8%) |
| Repeated phrases with simulated history | 600 | 285 (47.5%) | 303 (50.5%) | 230 (38.3%) |
| General text with simulated history | 600 | 142 (23.7%) | 163 (27.2%) | 220 (36.7%) |

In the conversational replay, inference and ranking took 34/69 ms p50/p95
for the first pass and 109/227 ms for the pause pass, excluding the 220 ms
delay. The first two positions are held fixed during the pause pass.

### Phrase learning curve against Gboard (2026-11-23, Nothing A024)

`GboardComparisonDeviceTest` types six invented phrases into each keyboard (Gboard with
real key taps, Nboard through its key handler), one exposure per round in rotated order,
and probes after each round with the full lead-in, only the last two or three words, and
a different opening. Run `phraseLearningCurve` (`-e gboard 1`) and
`nboardPhraseLearningCurve` (`-e nboard 1`) with `-e set b -e rounds 3`; a keyboard that
has already seen a set cannot measure its first exposures again. Nboard's learned data
is restored afterwards; Gboard keeps the practice phrases in its own history.

Top-three hits out of 6, after 1 / 2 / 3 exposures:

| Probe | Gboard 18.4 | Nboard before | Nboard after |
|---|---|---|---|
| Full lead-in | 2 / 3 / 5 | 1 / 3 / 5 | 1 / 6 / 6 |
| Last two words | 2 / 4 / 5 | 0 / 1 / 1 | 1 / 6 / 6 |
| Last three words | 2 / 3 / 4 | 0 / 1 / 2 | 1 / 6 / 6 |
| Different opening | 2 / 2 / 3 | 0 / 2 / 5 | 1 / 6 / 6 |

Gboard can surface a word after one use and leans on the last two words. Nboard keeps
its two-observation rule, so one stray word does not become a habit. Before the change,
general words outranked a recalled word at short contexts, and rare recalled words were
cut before ranking. The strongest recalled continuation now keeps the last slot.

On the 1,200-case habit replay, top-three hits on repeated phrases went from 285 to 296
(paused 303 to 313); general text stayed at 142.

### Held-out next words against Gboard (2026-11-23, Nothing A024)

`GboardComparisonDeviceTest#nextWordComparison` with `-e fullType 1` types each context
with keys into the keyboard (Gboard ignores text placed in the field), then reads the
strip. 100 cases from `nextword_test.tsv` whose context is letters and spaces only:

| Keyboard | Top three, all 100 | Top three, the 76 where Gboard showed suggestions |
|---|---:|---:|
| Gboard 18.4 | 12 | 12 |
| Nboard | 19 | 13 |

Gboard showed its toolbar instead of suggestions after 24 contexts. Where only Gboard
had the word, it was a very common conversational continuation ("would you" -> "like",
"have you ever" -> "had", "thank you" -> "so"), where Nboard ranked rarer words. Weighting
of frequent conversational pairs is the next ranking experiment; tune it on the dev set
with `fit_blend.py`, not on these cases. The sample is small and both keyboards kept
their own history.

### Conversational word triples (2026-11-23)

`build_chat_trigrams.py` counts word triples in Google's Taskmaster-1/2 dialogues
(CC BY 4.0, 645k utterances; separate from the SMS/DailyDialog cases) into
`english_chat_trigrams.txt` (59k triples). The ranking adds `weight * ln P(next | last two
words)` when the context is in the table (0.1 before the first letter, 0.6 after one), and a
continuation seen at least half the time ("would you" -> "like", 92%) keeps the strip's last
slot unless a recalled phrase holds it. Weights were fitted on the dev split from
`PredictionBlendDumpDeviceTest`; the word-pair counts alone gave no gain there.

Held-out test, Nothing A024, 1,200 cases, no history:

| | Top-1 | Top-3 | Top-3 after 1 letter | Boundary p50/p95 |
|---|---|---|---|---|
| Before | 18.6% | 29.0% | 59.1% | 24 / 43 ms |
| With chat triples | 18.3% | 29.5% | 59.4% | 23 / 40 ms |

Dialog cases gained most (top-3 35.1% to 36.2%). The examples Gboard won earlier now appear:
"would you" -> like, "call me back when she" -> gets, "soon you will" -> have,
"thank you so" -> much. "have you ever" -> had and "thank you" -> so still do not. Taskmaster is
mostly booking conversations, so the table is weakest outside that kind of talk.
