# Local prediction model shortlist

Researched 2026-10-05. The English prediction engine now integrates the first candidate through a pinned
llama.cpp CPU runtime. The remaining models are comparison candidates. Device
benchmark results are recorded separately in `keyboard-improvements.md`.

## First keyboard-specific candidate

[MrBob1337/keyboard-lm-daen](https://huggingface.co/MrBob1337/keyboard-lm-daen)
publishes a 65.7M-parameter Llama architecture model trained for English/Danish
keyboard prediction and the FUTO-style autocorrect protocol. Context length is
512 tokens. The Q6_K GGUF is **54,763,904 bytes**; the Q8_0 alternative is
70,675,520 bytes. These are file sizes, not runtime RAM requirements.

The repository's LICENSE explicitly assigns **CC-BY-SA-4.0** to weights, tokenizer,
and dictionary, and MIT to scripts. The original FUTO application's license is
separate from this independently trained model's license.

Pinned artifact:

- Repository revision: `0bc4e92782575cdb324328c523a6649a7615ece4`
- File: `daen-xbu-q6_k.gguf`
- SHA-256: `47b3a97a80ab96df2c7148b8bb155f471d0fe74bffed392c0c4ea220d36ea5f8`
- Development cache: `/mnt/media/AndroidDev/nboard-models/daen-xbu-q6_k.gguf`

The author's reported evaluations are next-token metrics using different models'
own tokenizers. They do not establish comparative complete-word prediction quality
or superiority to Apple/Gboard. Its bilingual vocabulary also requires testing for
unwanted Danish suggestions in English input. FUTO-specific correction tokens need
an adapter before its autocorrect capability can be used.

## Comparison candidates

| Model | Available artifact | License | Assessment |
| --- | --- | --- | --- |
| [SmolLM2-135M base](https://huggingface.co/HuggingFaceTB/SmolLM2-135M) | [QuantFactory Q4_K_M GGUF](https://huggingface.co/QuantFactory/SmolLM2-135M-GGUF), 105,453,536 bytes; Q8_0, 144,810,464 bytes | Apache-2.0 | English causal language model with documented pretraining. Useful baseline; not trained specifically for keyboards. |
| [Onit Keyboard-LM](https://huggingface.co/getonit/Keyboard-LM) | Core ML package with 81,119,936-byte FP16 weight file, model graph, and English tokenizer | Apache-2.0 | About 40M parameters, trained for English next-word prediction. Published files have no PyTorch checkpoint, ONNX export, or GGUF; an Android port needs conversion and numerical parity validation. |
| [Qwen2.5-0.5B base](https://huggingface.co/Qwen/Qwen2.5-0.5B) | Public original weights, multiple conversion options | Apache-2.0 | Larger candidate for later quality comparisons. No Nboard latency measurements. |

## First evaluation

Use the 55 MB keyboard model and SmolLM2 as comparison candidates. Evaluate completed
words and partial-word completions on the same held-out English text, alongside
Nboard's existing engine. Decode complete words rather than presenting arbitrary
top subword tokens as suggestions. Measure top-three word recall, ranking quality,
warm/cold inference latency, peak memory, and sustained typing performance.

Keep the existing immediate dictionary response while inference runs on one
background worker. Neural rankings must use a context version so outdated results
cannot replace predictions after typing, cursor movement, or field changes.

## Implemented adapter

The model's GGUF omits `tokenizer.ggml.add_space_prefix`, despite its training
script using `add_dummy_prefix=False`. Nboard explicitly overrides that metadata
to false. It keeps real trailing spaces, uses the suffix-space vocabulary, and
never interprets field text as special instructions or autocorrect control tokens.

The runtime scores complete candidate words, including every subword and the
terminating space. Candidates share at most 128 cached context tokens. New words
from the model's most likely whole-word tokens must also exist in the English
frequency dictionary or the user's learned vocabulary. French continues to use
the French dictionary; this English/Danish model is not applied to French input.

The quantized file and its licensing notices are bundled in `assets/models`.
First installation copies it atomically to app-private, backup-excluded storage
and verifies the pinned SHA-256. Predictions run entirely offline. Turning off
word predictions disables the entire pipeline, and password/private fields are
excluded. A fast dictionary worker stays independent of one neural worker;
both mailboxes conflate pending requests and discard stale field/cursor results.

The native source download is pinned by commit and archive SHA-256 in CMake.
Reproduce the Android build with NDK 27.1.12297006 and CMake 3.22.1. Development
host benchmarks use the same `prediction.cpp` implementation, built by CMake.
