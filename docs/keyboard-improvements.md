# Keyboard improvements

Requested scope: improvements 1–12 except 7. Item 7 was reducing the existing
5% key-scale press animation; that animation remains unchanged.

| Item | Implementation |
| --- | --- |
| 1. Unified ranking | Dictionary frequency, context frequency, personal frequency, recency, learned bigrams/trigrams and rejection penalties compete in one score. |
| 2. Typo-tolerant suggestions | Single substitutions, insertions, deletions and adjacent transpositions can produce completions. Short prefixes stay exact. Recognized words skip the broad apostrophe-variant correction search on delimiters; explicit typo mappings still apply. |
| 3. Longer context | Offline 65.7M keyboard model, Q6_K, complete-word likelihood, 128 cached context tokens; English only. |
| 4. Better personalization | Persist last-use times, favor recent/repeated/explicitly accepted words, reduce unwanted corrections after undo; fix double-counting ordinary typed words. |
| 5. Background prediction | Dictionary and neural workers have conflated mailboxes. One model inference runs at a time; dictionary updates remain independent. Field/context revisions reject stale results. Typo lookup uses a binary prefix index instead of scanning thousands of dictionary entries. Capitalization and prediction share one fresh editor context per UI refresh; letters skip punctuation-only editor queries. |
| 6. Reuse key views | Shift/caps update existing labels and shift icon. Rebuild when layout, theme, font, symbols or variants change. |
| 7. Reduce key-scale movement | Excluded at the user's request. |
| 8. Stable rankings | Keep a previous slot for a near-tied score; replace it when the new candidate is meaningfully better. Letter motion remains clipped to fixed slots. |
| 9. Streaming AI | ChatGPT Responses, OpenAI-compatible chat completions, Anthropic and Gemini streams publish partial preview text, throttled to 80ms. Stop cancels the socket. |
| 10. Review/apply/undo | Completed output is previewed before Apply. Field, selection and surrounding-text checks guard Apply. Undo checks the original field, location and inserted text. |
| 11. Task-specific edits | Grammar makes minimal changes; summary preserves names/numbers/commitments; expansion preserves facts and tone. Selected whitespace and paragraphs are retained. |
| 12. Measurements | Reproducible synthetic complete-word fixture benchmark against the previous BigramPredictor; real IME frame, key-commit and process-memory measurements. |

Additional requested changes: letter press previews above the finger; `?` in place
of `=` on the second page, with `=` available on the extended symbols page.
Persistent 50-item clipboard, emoji picker, hold-letter variants, gentle haptics,
neutral prediction styling and per-grapheme prediction animation are retained.

## Validation

The 20 phrase fixtures in `tools/prediction-cases.tsv` are a development smoke
benchmark, not a representative language corpus or proof of Apple/Gboard parity.
The complete device benchmark does not supply expected answers to the model.
Host model-load/adapter smoke checks are separate from accuracy measurements.

On the Nothing phone, the revised hybrid placed the expected word in its top three
for **13/20** development phrases, versus **6/20** for the existing predictor and
**5/20** for dictionary ranking alone. Warm end-to-end engine latency was
**33.3ms median / 129.3ms p95**; the first cold request was **585.5ms**.
Dictionary feedback runs independently while model refinement is pending.
These results are from fixed development fixtures and do not establish general
accuracy. Reports are saved under the ignored `release-assets` directory.

Device checks exercise the actual input-method window, key preview, shift view
identity, punctuation placement, streamed preview, Apply, Undo, cursor-change
rejection and Stop. Cloud-provider responses are deterministic test doubles for
these UI checks. Subscription-account authorization remains a user action;
no real account inference is claimed by mocked UI tests.

The installed build passes **94 JVM tests**, **all 8 device tests**, and lint with
**zero errors** (232 warnings remain). Device coverage also checks double-period
spacing, selected-text replacement, preservation of `thank`, and the explicit
`cant` → `can't` correction. The editor-query optimization updates its local
punctuation context when removing an automatically inserted space.

In the final separate-process typing run, key commits measured **7.7ms median /
20.2ms p95**, compared with **7.5ms / 107.9ms** before skipping the unnecessary
correction search for recognized words. One space commit still took **231.5ms**.
IME frames measured **10.0ms median / 31.6ms p95**, so occasional frame jank
remains. These are workload-dependent development measurements, not a guarantee
of native-level smoothness. The same-process stress editor recorded frame p95
**19.0ms** and key-commit p95 **5.8ms**. Its process-memory reading includes
instrumentation and benchmark allocations and is not a standalone keyboard RAM
measurement. Slow-key reports contain only synthetic fixture indices, key
categories and numeric timings; autocorrect timing logs omit field text.

Battery drain and hours-long typing quality require a longer controlled trial.
This revision does not claim those have been measured.

The native tokenizer matches the published SentencePiece tokenizer on ten Latin,
accented, complete-word and trailing-space fixtures (including the initial BOS).

Model refinement reduces the raw unigram/bigram prior weight, preserving the full
personalization and typo penalties. A credible exact prefix receives a stronger
penalty against speculative typo matches. Repeated learned names have a bounded
neural likelihood floor, so unfamiliar subword spellings do not erase the user's
own vocabulary evidence.

Performance is measured both in the same-process development editor (a stress
case) and a separate-process editor shipped only in the instrumentation APK.
The latter reflects ordinary Android input-connection behavior and avoids making
keyboard/editor calls block on the same main thread. Timings include the user's
phone workload; they are not isolated laboratory measurements.
