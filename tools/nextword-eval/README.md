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
