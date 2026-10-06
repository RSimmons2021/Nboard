// Tokenizer-independent next-word benchmark for GGUF language models.
//
// Every model gets the same procedure: the message so far as context, a beam search
// constrained to the same dictionary words, whole-word log-likelihoods and, for
// tokenizers that mark word starts (" word", "▁word"), the probability that the word
// ends there. Reports top-1/top-3 accuracy against the word actually typed next.
//
// usage: nextword_eval model.gguf english_50k.txt cases.tsv [--lowercase] [--words N] [--threads N] [--limit N]
// cases.tsv: context<TAB>expected<TAB>source
#include "llama.h"
#include <algorithm>
#include <array>
#include <cctype>
#include <chrono>
#include <cmath>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <iostream>
#include <map>
#include <sstream>
#include <string>
#include <vector>

namespace {
const std::string SPACE_MARK = "\xe2\x96\x81";  // ▁ (SentencePiece)
const std::string GPT2_SPACE = "\xc4\xa0";      // Ġ (byte-level BPE)

struct Node { std::map<llama_token, int> children; std::string word; };

double logAdd(double a, double b) {
    if (a == -INFINITY) return b;
    if (b == -INFINITY) return a;
    double m = std::max(a, b);
    return m + std::log(std::exp(a - m) + std::exp(b - m));
}

struct Evaluator {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    const llama_vocab *vocab = nullptr;
    int nVocab = 0;
    bool leading = true;          // word-start markers (" word") vs word-end markers ("word▁")
    std::vector<char> boundary;   // leading style: tokens that begin a new word, punctuation or end
    std::vector<Node> trie{1};
    static constexpr int kSeqMax = 64;

    std::vector<llama_token> tokenize(const std::string &text, bool bos) const {
        std::vector<llama_token> out(text.size() * 2 + 8);
        int n = llama_tokenize(vocab, text.data(), text.size(), out.data(), out.size(), bos, false);
        if (n < 0) { out.resize(-n); n = llama_tokenize(vocab, text.data(), text.size(), out.data(), out.size(), bos, false); }
        out.resize(std::max(n, 0));
        return out;
    }

    void open(const std::string &path, int threads, bool noSpacePrefix) {
        llama_backend_init();
        auto mp = llama_model_default_params();
        mp.n_gpu_layers = 0;
        // Same override as the app (prediction.cpp): the keyboard model's GGUF omits this flag.
        static llama_model_kv_override overrides[2] = {};
        if (noSpacePrefix) {
            overrides[0].tag = LLAMA_KV_OVERRIDE_TYPE_BOOL;
            std::strcpy(overrides[0].key, "tokenizer.ggml.add_space_prefix");
            overrides[0].val_bool = false;
            mp.kv_overrides = overrides;
        }
        model = llama_model_load_from_file(path.c_str(), mp);
        if (!model) throw std::runtime_error("model load failed");
        vocab = llama_model_get_vocab(model);
        nVocab = llama_vocab_n_tokens(vocab);
        auto cp = llama_context_default_params();
        cp.n_ctx = 1024; cp.n_batch = 256; cp.n_ubatch = 256; cp.n_seq_max = kSeqMax;
        cp.n_threads = threads; cp.n_threads_batch = threads; cp.kv_unified = true;
        ctx = llama_init_from_model(model, cp);
        if (!ctx) throw std::runtime_error("context failed");
        // A word-end vocabulary has whole words ending in ▁ ("the▁").
        int trailing = 0, leadingCount = 0;
        for (int t = 0; t < nVocab; ++t) {
            std::string s = llama_vocab_get_text(vocab, t);
            if (s.size() > SPACE_MARK.size() && s.compare(s.size() - SPACE_MARK.size(), SPACE_MARK.size(), SPACE_MARK) == 0) ++trailing;
            if (s.rfind(SPACE_MARK, 0) == 0 || s.rfind(GPT2_SPACE, 0) == 0) ++leadingCount;
        }
        leading = leadingCount >= trailing;
        boundary.assign(nVocab, 0);
        for (int t = 0; t < nVocab; ++t) {
            std::string s = llama_vocab_get_text(vocab, t);
            bool startsWord = s.rfind(SPACE_MARK, 0) == 0 || s.rfind(GPT2_SPACE, 0) == 0 || s.rfind("\xc4\x8a", 0) == 0;
            bool punct = !s.empty() && std::ispunct(static_cast<unsigned char>(s[0])) && s[0] != '\'';
            boundary[t] = startsWord || punct || llama_vocab_is_eog(vocab, t) || s == "\n" || s == "<0x0A>";
        }
        std::cerr << "tokenizer: " << (leading ? "word-start" : "word-end") << " markers, vocab " << nVocab << "\n";
    }

    void addWord(const std::string &surface, const std::string &key) {
        auto tokens = tokenize(leading ? " " + surface : surface + " ", false);
        if (tokens.empty() || tokens.size() > 5) return;
        int node = 0;
        for (auto t : tokens) {
            auto it = trie[node].children.find(t);
            if (it == trie[node].children.end()) {
                trie.push_back({});
                int next = trie.size() - 1;
                trie[node].children[t] = next;
                node = next;
            } else node = it->second;
        }
        trie[node].word = key;
    }

    void decode(llama_batch &batch) {
        if (llama_decode(ctx, batch) != 0) throw std::runtime_error("decode failed");
    }

    static std::vector<double> logSoftmax(const float *logits, int n) {
        float m = *std::max_element(logits, logits + n);
        double sum = 0;
        for (int i = 0; i < n; ++i) sum += std::exp(logits[i] - m);
        double norm = m + std::log(sum);
        std::vector<double> out(n);
        for (int i = 0; i < n; ++i) out[i] = logits[i] - norm;
        return out;
    }

    double boundaryLogProb(const float *logits) const {
        float m = *std::max_element(logits, logits + nVocab);
        double all = 0, ends = 0;
        for (int i = 0; i < nVocab; ++i) { double p = std::exp(logits[i] - m); all += p; if (boundary[i]) ends += p; }
        return std::log(std::max(ends, 1e-30) / all);
    }

    /** Ranked (word, log-probability), best first. */
    std::vector<std::pair<std::string, double>> predict(const std::string &rawContext) {
        auto memory = llama_get_memory(ctx);
        llama_memory_clear(memory, true);
        // Word-start tokenizers attach the space to the next word.
        std::string context = rawContext;
        if (leading) while (!context.empty() && context.back() == ' ') context.pop_back();
        auto tokens = tokenize(context, true);
        if (tokens.size() > 128) { // keep BOS + the most recent context
            std::vector<llama_token> kept{tokens.front()};
            kept.insert(kept.end(), tokens.end() - 127, tokens.end());
            tokens.swap(kept);
        }
        int position = tokens.size();
        {
            auto batch = llama_batch_init(tokens.size(), 0, 1);
            batch.n_tokens = tokens.size();
            for (size_t i = 0; i < tokens.size(); ++i) {
                batch.token[i] = tokens[i]; batch.pos[i] = i; batch.n_seq_id[i] = 1; batch.seq_id[i][0] = 0;
                batch.logits[i] = i + 1 == tokens.size();
            }
            decode(batch); llama_batch_free(batch);
        }
        auto base = logSoftmax(llama_get_logits_ith(ctx, -1), nVocab);

        struct State { int node; llama_token token; double score; int parentSeq; int seq; std::vector<llama_token> path; };
        struct Done { std::string word; double score; std::vector<llama_token> path; };
        std::vector<Done> done;
        std::vector<State> frontier;
        for (auto &[t, child] : trie[0].children) {
            double s = base[t];
            if (!trie[child].word.empty()) done.push_back({trie[child].word, s, {t}});
            if (!trie[child].children.empty()) frontier.push_back({child, t, s, 0, 0, {t}});
        }
        constexpr int width = 16;
        for (int depth = 1; depth < 5 && !frontier.empty(); ++depth) {
            std::sort(frontier.begin(), frontier.end(), [](auto &a, auto &b) { return a.score > b.score; });
            if (frontier.size() > width) frontier.resize(width);
            const int bank = depth % 2 ? 1 : width + 1;
            auto batch = llama_batch_init(frontier.size(), 0, 1);
            batch.n_tokens = frontier.size();
            for (size_t i = 0; i < frontier.size(); ++i) {
                auto &s = frontier[i];
                s.seq = bank + i;
                llama_memory_seq_rm(memory, s.seq, -1, -1);
                llama_memory_seq_cp(memory, s.parentSeq, s.seq, -1, -1);
                batch.token[i] = s.token; batch.pos[i] = position + depth - 1;
                batch.n_seq_id[i] = 1; batch.seq_id[i][0] = s.seq; batch.logits[i] = true;
            }
            decode(batch); llama_batch_free(batch);
            std::vector<State> next;
            for (size_t i = 0; i < frontier.size(); ++i) {
                auto lp = logSoftmax(llama_get_logits_ith(ctx, i), nVocab);
                for (auto &[t, child] : trie[frontier[i].node].children) {
                    double s = frontier[i].score + lp[t];
                    auto path = frontier[i].path; path.push_back(t);
                    if (!trie[child].word.empty()) done.push_back({trie[child].word, s, path});
                    if (!trie[child].children.empty()) next.push_back({child, t, s, frontier[i].seq, 0, path});
                }
            }
            frontier.swap(next);
        }
        for (int s = 1; s < kSeqMax; ++s) llama_memory_seq_rm(memory, s, -1, -1);

        std::sort(done.begin(), done.end(), [](auto &a, auto &b) { return a.score > b.score; });
        if (leading && !std::getenv("NO_BOUNDARY")) {
            // P(word ends here) for the strongest candidates: "mon" must not inherit "monday".
            size_t n = std::min<size_t>(done.size(), 24);
            for (size_t start = 0; start < n; start += kSeqMax - 1) {
                size_t end = std::min(n, start + kSeqMax - 1);
                int total = 0;
                for (size_t i = start; i < end; ++i) total += done[i].path.size();
                auto batch = llama_batch_init(total, 0, 1);
                batch.n_tokens = 0;
                std::vector<int> lastIndex;
                for (size_t i = start; i < end; ++i) {
                    int seq = 1 + (i - start);
                    llama_memory_seq_rm(memory, seq, -1, -1);
                    llama_memory_seq_cp(memory, 0, seq, -1, -1);
                    for (size_t k = 0; k < done[i].path.size(); ++k) {
                        int b = batch.n_tokens++;
                        batch.token[b] = done[i].path[k]; batch.pos[b] = position + k;
                        batch.n_seq_id[b] = 1; batch.seq_id[b][0] = seq; batch.logits[b] = k + 1 == done[i].path.size();
                    }
                    lastIndex.push_back(batch.n_tokens - 1);
                }
                decode(batch); llama_batch_free(batch);
                for (size_t i = start; i < end; ++i) done[i].score += boundaryLogProb(llama_get_logits_ith(ctx, lastIndex[i - start]));
                for (int s = 1; s < kSeqMax; ++s) llama_memory_seq_rm(memory, s, -1, -1);
            }
            done.resize(n);
        }
        std::map<std::string, double> merged; // case variants of one word share their probability
        for (auto &d : done) merged[d.word] = logAdd(merged.count(d.word) ? merged[d.word] : -INFINITY, d.score);
        std::vector<std::pair<std::string, double>> out(merged.begin(), merged.end());
        std::sort(out.begin(), out.end(), [](auto &a, auto &b) { return a.second > b.second; });
        return out;
    }
};
}

int main(int argc, char **argv) {
    if (argc < 4) { std::cerr << "usage: nextword_eval model.gguf words.txt cases.tsv [--lowercase] [--words N] [--threads N] [--limit N]\n"; return 1; }
    bool lowercase = false, noSpacePrefix = false;
    int wordCount = 30000, threads = 4, limit = 1 << 30;
    for (int i = 4; i < argc; ++i) {
        std::string a = argv[i];
        if (a == "--lowercase") lowercase = true;
        else if (a == "--no-space-prefix") noSpacePrefix = true;
        else if (a == "--words") wordCount = std::stoi(argv[++i]);
        else if (a == "--threads") threads = std::stoi(argv[++i]);
        else if (a == "--limit") limit = std::stoi(argv[++i]);
    }
    llama_log_set([](ggml_log_level, const char *, void *) {}, nullptr);
    Evaluator e;
    e.open(argv[1], threads, noSpacePrefix);
    std::ifstream words(argv[2]);
    std::string line;
    for (int n = 0; n < wordCount && std::getline(words, line);) {
        std::string w = line.substr(0, line.find(' '));
        if (w.empty() || w.find_first_not_of("abcdefghijklmnopqrstuvwxyz'") != std::string::npos) continue;
        e.addWord(w, w);
        if (!lowercase) { std::string cap = w; cap[0] = std::toupper(cap[0]); if (cap != w) e.addWord(cap, w); }
        ++n;
    }
    std::ifstream cases(argv[3]);
    std::map<std::string, std::array<int, 3>> stats; // source -> cases, top1, top3
    double totalMs = 0; int n = 0;
    while (n < limit && std::getline(cases, line)) {
        std::istringstream in(line);
        std::string context, expected, source;
        std::getline(in, context, '\t'); std::getline(in, expected, '\t'); std::getline(in, source, '\t');
        if (lowercase) std::transform(context.begin(), context.end(), context.begin(), ::tolower);
        auto start = std::chrono::steady_clock::now();
        auto ranked = e.predict(context);
        totalMs += std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - start).count();
        int rank = 99;
        for (size_t i = 0; i < ranked.size() && i < 3; ++i) if (ranked[i].first == expected) rank = i;
        for (auto key : {source, std::string("all")}) {
            auto &s = stats[key]; s[0]++; if (rank == 0) s[1]++; if (rank < 3) s[2]++;
        }
        std::cout << expected << "\t" << (ranked.size() > 0 ? ranked[0].first : "") << "," << (ranked.size() > 1 ? ranked[1].first : "")
                  << "," << (ranked.size() > 2 ? ranked[2].first : "") << "\t" << source << "\n";
        ++n;
    }
    for (auto &[source, s] : stats)
        std::cerr << "RESULT " << source << " n=" << s[0] << " top1=" << 100.0 * s[1] / s[0] << "% top3=" << 100.0 * s[2] / s[0] << "%\n";
    std::cerr << "RESULT avg_ms=" << totalMs / std::max(n, 1) << "\n";
}
