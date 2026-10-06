# Improving next-word prediction

The implemented engine is a starting point. Its 13/20 development result combines
partial-word completion and next-word prediction; it is not a comparison with
Apple, Gboard or Samsung. A separate 64-case next-word development benchmark now compares candidate coverage and top-three recall with an empty typed prefix. It uses synthetic sentences and does not measure commercial-keyboard parity.

## What already exists

- Capture up to 800 characters before the cursor from the current editor.
- Score English suggestions using up to 128 cached context tokens in the 65.7M
  keyboard model. French/bilingual mode currently uses dictionary ranking.
- Persist word frequency, recency, learned bigrams/trigrams and rejection feedback.
- Recall recurring continuations from two to five preceding words. Keep at most
  2,048 continuation records, with language-mode/app scope and global fallback.
  Require two observations, combine overlapping matches by their strongest score,
  and prefer the longest context when four/five-word matches have at least three
  observations; use shorter suffixes as fallback when that evidence is absent,
  and include recalled words before neural ranking. Save snapshots through an
  ordered background writer. Learn only words committed through the keyboard;
  retract the latest phrase observation on a backspace edit to its word or when a
  correction is undone.
  Password/no-personalization fields do not learn phrases. Text correction settings
  provide a button to clear learned words, phrases and correction preferences.
- Discover whole English words through a dictionary tokenizer trie, with eight
  live branches, at most six tokens per word and 24 returned candidates. Candidate
  likelihood includes the final space, so subword fragments never reach the strip.
- Cache model logits and whole-word likelihoods for the current sentence context,
  reusing them while the user types letters. Invalidate on context changes and
  bound the probability cache to 512 entries. Trie search stops branches that
  cannot enter its 24 returned candidates. Word boundaries bypass the prefix
  debounce to help the model meet the prediction-row coalescing deadline.
- Publish dictionary suggestions immediately and refine them asynchronously.
  At a word boundary, quick results are combined within an 80 ms deadline to
  avoid briefly showing one row before replacing it. Letter updates stay immediate.
- Recognize valid words from the loaded correction dictionaries as well as the
  older lexicons, so normal words do not enter a broad spelling-variant search.

## Limits in the current implementation

The neural stage scores the first 24 locally ranked candidates and retains the
16 single-token word candidates. At an empty prefix, bounded trie search also
adds complete multi-token words from the 50k English dictionary. The beam can
still prune a useful word; candidate recall must be evaluated separately from
ranking. Longer unknown names enter via personal vocabulary/phrase recall.

Model and dictionary weights are hand-tuned. Personalization now retrieves recurring phrase suffixes and distinguishes apps,
but names are still normalized to lowercase and inferred casing uses the current
word/sentence style.
The current model does not adapt its neural weights to the user's typing.

## Development measurements on Nothing A024 (2026-10-05)

The 64 synthetic next-word cases use an empty prefix and no learned history.
Expected words are used only for evaluation, never passed into candidate search.
The two searches run in separate model instances with the same cache conditions.
These are development examples with one expected continuation each, not a large
held-out corpus or a measurement against installed commercial keyboards.

| Measure | Previous search | Dictionary trie search |
| --- | --- | --- |
| Expected word in candidate pool | 44 / 64 | 53 / 64 |
| Expected word in visible top three | 34 / 64 | 34 / 64 |

Warm dictionary-plus-model latency in the latest run was approximately 61 ms
median and 80 ms at the 95th percentile. Startup and UI/worker scheduling are
excluded; this is not an end-to-end key-to-screen measurement. Wider search
improved coverage without improving top-three recall in this small sample.
Ranking calibration and a stronger conversational model remain necessary work.

A separate phone regression checks that recurring five-word context can promote
its learned continuation through neural ranking despite a conflicting shorter
phrase. Device checks also cover privacy guards, ordered persistence/clear,
backspace edits and correction undo after punctuation. A separate external-editor
typing run recorded key-commit median 7.5 ms / p95 20.5 ms, with no warm commits
above 30 ms; frame p95 was 30.8 ms, so some rendering jitter remains.
JVM tests include temporal
history use, capacity limits, recency, language/app scope and sentence boundaries.

## Implementation order

1. **Establish a next-word benchmark.** Use thousands of held-out conversational
   word boundaries, with an empty typed prefix. Report top-one/top-three recall,
   candidate recall before ranking, keystrokes saved and warm/cold phone latency.
   Evaluate completion, spelling correction and personal vocabulary separately.
   A temporal split must keep learned history older than the evaluated examples.
   Use identical public/synthetic inputs when comparing installed keyboards.

2. **Expand complete-word candidates.** Generate candidates through a bounded
   tokenizer/trie search that can finish multi-token words. Include local
   vocabulary and recalled phrase continuations. Rank all candidates using the
   probability of the whole word, including its boundary. Track candidate recall
   so failures can be attributed to generation or ranking.

3. **Add persistent phrase memory.** Keep a bounded local index of recurring
   short phrases with frequency, last-use time and app scope. Match the current
   context to useful continuations. For example, repeated use of "I'll be there
   in ten minutes" should support "ten" after "I'll be there in". Feed recalled
   continuations into candidate generation and ranking, with global fallback
   when app-specific history is sparse. Preserve useful capitalization and names.
   Learn confirmed text rather than transient compositions; remove evidence
   when a correction is undone. Honor existing private/password-field rules and
   provide a way to clear learned data. The bounded phrase index is implemented; preserving learned capitalization is
   still pending.

4. **Calibrate ranking.** Fit the mixture of neural probability, phrase memory,
   personal recency and dictionary frequency on held-out examples. Improve
   sentence-boundary handling and grammatical continuations. Require evidence
   before suppressing an exact prefix or a familiar name.

5. **Evaluate a conversational model.** Compare the current model against an
   English base model such as SmolLM2-135M/360M with keyboard-specific training
   or a trained whole-word prediction head. A generic chat model is a comparison
   candidate, not a demonstrated upgrade. Each tokenizer needs its own validated
   adapter; replacing the current GGUF alone would not be sufficient. Measure
   quality, latency, memory and sustained typing before selecting a replacement.

6. **Keep prediction off the input path.** Cache sentence-conditioned scores at
   word boundaries and filter them cheaply while letters are typed. Keep the
   immediate dictionary path, stale-result guards and stable slots. Evaluate
   touch-aware typo scoring separately; it cannot replace next-word semantics.

An ordinary Android keyboard can learn text entered through it and obtain text
around the cursor in the active editor. This does not give it a history of every
conversation in other apps. Historical context would accumulate locally as the
user types with nBoard.

## Primary references

- [Apple: predictive text uses past conversations and writing style](https://support.apple.com/en-au/104995).
- [Google: context-aware neural search in the Gboard decoder](https://aclanthology.org/2024.emnlp-industry.93/).
- [Google: federated personalization research](https://research.google/blog/a-scalable-approach-for-partially-local-federated-learning/).
- [Current keyboard model and training notes](https://huggingface.co/MrBob1337/keyboard-lm-daen).
- [SmolLM2 base model documentation](https://huggingface.co/HuggingFaceTB/SmolLM2-360M).
- [An independent Android keyboard's whole-word scoring implementation report](https://lexokeyboard.app/writeups/on-device-llm-keyboard/).
- [Android editor context API](https://developer.android.com/reference/android/view/inputmethod/InputConnection).

These sources describe approaches and available artifacts. They do not establish
that this fork matches the quality of any commercial keyboard.
