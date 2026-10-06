#include "prediction.h"
#include <algorithm>
#include <cctype>
#if defined(__ANDROID__)
#include <android/log.h>
#include <dlfcn.h>
#include "ggml-backend.h"
#endif
#include <cmath>
#include <cstring>
#include <stdexcept>
#include <mutex>
#include <set>

namespace {
double logSumExp(const float *logits, int count) {
    float maximum = *std::max_element(logits, logits + count);
    float total = 0;
    for (int i = 0; i < count; ++i) total += std::exp(logits[i] - maximum);
    return maximum + std::log(static_cast<double>(total));
}
double logProbability(const float *logits, int count, int token) {
    return logits[token] - logSumExp(logits, count);
}
const std::string SPM_SPACE = "\xe2\x96\x81";  // ▁
const std::string BPE_SPACE = "\xc4\xa0";       // Ġ (byte-level BPE)
bool startsWith(const std::string &s, const std::string &p) { return s.compare(0, p.size(), p) == 0; }
std::string capitalized(std::string word) { if (!word.empty() && word[0] >= 'a' && word[0] <= 'z') word[0] -= 32; return word; }
/** The next word starts a sentence: empty context or one ending in . ! ? or a new line. */
bool sentenceStart(const std::string &context) {
    auto end = context.find_last_not_of(' ');
    return end == std::string::npos || std::strchr(".!?\n", context[end]) != nullptr;
}
bool isWord(const std::string &word) {
    if (word.empty() || word.size() > 48) return false;
    for (unsigned char c : word) if (!(c >= 128 || (c >= 'a' && c <= 'z') || c == '\'')) return false;
    return true;
}
}

#if defined(__ANDROID__)
/**
 * Loads the fastest CPU module this phone supports. Modules live inside the APK, so they
 * are opened by file name (the app linker namespace finds them) rather than by scanning a
 * directory. Each module's ggml_backend_score() reports 0 when the CPU lacks its features.
 */
void loadBestCpuBackend() {
    static const char *variants[] = {
        "libggml-cpu-android_armv9.2_2.so", "libggml-cpu-android_armv9.2_1.so", "libggml-cpu-android_armv9.0_1.so",
        "libggml-cpu-android_armv8.6_1.so", "libggml-cpu-android_armv8.2_2.so", "libggml-cpu-android_armv8.2_1.so",
        "libggml-cpu-android_armv8.0_1.so",
    };
    const char *best = nullptr;
    int bestScore = 0;
    for (const char *name : variants) {
        void *handle = dlopen(name, RTLD_NOW | RTLD_LOCAL);
        if (!handle) continue;
        auto score = reinterpret_cast<int (*)()>(dlsym(handle, "ggml_backend_score"));
        int value = score ? score() : 0;
        dlclose(handle);
        if (value > bestScore) { bestScore = value; best = name; }
    }
    if (!best || !ggml_backend_load(best)) throw std::runtime_error("No usable CPU backend");
    __android_log_print(ANDROID_LOG_INFO, "NboardPrediction", "CPU backend %s", best);
}
#endif

KeyboardModel::KeyboardModel(const std::string &path, const std::vector<std::string> &words) {
    static std::once_flag initialized;
    std::call_once(initialized, [] {
        llama_backend_init();
#if defined(__ANDROID__)
        loadBestCpuBackend();
#endif
    });
    auto params = llama_model_default_params();
    params.n_gpu_layers = 0;
    // This keyboard model's tokenizer has no dummy prefix. Its GGUF omits the flag,
    // so llama.cpp's Llama default would otherwise silently prepend a wrong space.
    llama_model_kv_override overrides[2] = {};
    overrides[0].tag = LLAMA_KV_OVERRIDE_TYPE_BOOL;
    std::strcpy(overrides[0].key, "tokenizer.ggml.add_space_prefix");
    overrides[0].val_bool = false;
    params.kv_overrides = overrides;
    model = llama_model_load_from_file(path.c_str(), params);
    if (!model) throw std::runtime_error("Failed to load keyboard model");
    vocab = llama_model_get_vocab(model);
    auto cp = llama_context_default_params();
    cp.n_ctx = 768; cp.n_batch = 128; cp.n_ubatch = 128; cp.n_seq_max = 64;
    cp.n_threads = 2; cp.n_threads_batch = 2; cp.kv_unified = true;
    ctx = llama_init_from_model(model, cp);
    if (!ctx) { llama_model_free(model); model = nullptr; throw std::runtime_error("Failed to create model context"); }
    // The tokenizer style decides how words and their boundaries are scored:
    // word-end vocabularies ("the▁", this app's original model) or word-start
    // vocabularies (" the" / "▁the", general models such as SmolLM2).
    const int count = llama_vocab_n_tokens(vocab);
    int endMarked = 0, startMarked = 0;
    for (int t = 0; t < count; ++t) {
        std::string raw = llama_vocab_get_text(vocab, t);
        if (raw.size() > SPM_SPACE.size() && raw.compare(raw.size()-SPM_SPACE.size(), SPM_SPACE.size(), SPM_SPACE)==0) ++endMarked;
        if (startsWith(raw, SPM_SPACE) || startsWith(raw, BPE_SPACE)) ++startMarked;
    }
    wordStart = startMarked > endMarked;
    // Only complete word tokens are offered; subword fragments never reach the UI.
    for (int t = 0; t < count; ++t) {
        std::string raw = llama_vocab_get_text(vocab, t);
        if (!wordStart) {
            if (raw.size() > SPM_SPACE.size() && raw.compare(raw.size()-SPM_SPACE.size(), SPM_SPACE.size(), SPM_SPACE)==0) {
                auto word = raw.substr(0, raw.size()-SPM_SPACE.size());
                if (isWord(word)) completeWords.emplace_back(word, t);
            }
            continue;
        }
        const std::string &marker = startsWith(raw, BPE_SPACE) ? BPE_SPACE : SPM_SPACE;
        if (!startsWith(raw, marker)) continue;
        auto word = raw.substr(marker.size());
        if (isWord(word)) completeWords.emplace_back(word, t);
        else if (!word.empty() && word[0] >= 'A' && word[0] <= 'Z') {
            auto lower = word; lower[0] += 32;
            if (isWord(lower)) capitalWords.emplace_back(lower, t);
        }
    }
    for (const auto &word : words) {
        if (!isWord(word) || word.size() > 24) continue;
        if (!wordStart) { addToTree(wordTree, tokenize(word + " "), word); continue; }
        addToTree(wordTree, tokenize(" " + word), word);
        addToTree(capitalTree, tokenize(" " + capitalized(word)), word);
    }
}

void KeyboardModel::addToTree(std::vector<WordNode> &tree, const std::vector<llama_token> &pieces, const std::string &word) {
    if (pieces.empty() || pieces.size() > 6) return;
    size_t node = 0;
    for (auto token : pieces) {
        auto child = tree[node].children.find(token);
        if (child == tree[node].children.end()) {
            auto next = tree.size();
            tree[node].children[token] = next;
            tree.push_back({});
            node = next;
        } else node = child->second;
    }
    tree[node].word = word;
}

/** How [word] appears after the context: "word▁", " word" or, starting a sentence, " Word". */
std::string KeyboardModel::surface(const std::string &word) const {
    if (!wordStart) return word + " ";
    return " " + (capitalContext ? capitalized(word) : word);
}

KeyboardModel::~KeyboardModel() { if (ctx) llama_free(ctx); if (model) llama_model_free(model); }

std::vector<llama_token> KeyboardModel::tokenize(const std::string &text, bool bos) {
    std::vector<llama_token> tokens(text.size() * 2 + 8);
    int size = llama_tokenize(vocab, text.data(), text.size(), tokens.data(), tokens.size(), bos, false);
    if (size < 0) { tokens.resize(-size); size = llama_tokenize(vocab, text.data(), text.size(), tokens.data(), tokens.size(), bos, false); }
    if (size < 0) throw std::runtime_error("Tokenizer failure");
    tokens.resize(size); return tokens;
}

void KeyboardModel::decode(const std::vector<llama_token> &tokens, int position, int sequence) {
    auto batch = llama_batch_init(tokens.size(), 0, 1);
    batch.n_tokens = tokens.size();
    for (size_t i=0; i<tokens.size(); ++i) {
        batch.token[i] = tokens[i]; batch.pos[i] = position+i;
        batch.n_seq_id[i] = 1; batch.seq_id[i][0] = sequence;
        batch.logits[i] = i==tokens.size()-1;
    }
    int status = llama_decode(ctx, batch);
    llama_batch_free(batch);
    if (status != 0) throw std::runtime_error("Model decode failure");
}

std::vector<std::pair<std::string, double>> KeyboardModel::score(const std::string &context,
        const std::string &prefix, const std::vector<std::string> &candidates, bool discover) {
    // Word-start tokenizers attach the space to the following word.
    std::string text = context;
    if (wordStart) while (!text.empty() && text.back() == ' ') text.pop_back();
    capitalContext = wordStart && sentenceStart(text);
    auto tokens = tokenize(text, true);
    if (tokens.size() > 128) tokens.erase(tokens.begin(), tokens.end()-128);
    if (tokens.empty()) return {};
    auto memory = llama_get_memory(ctx);
    for (int sequence=1; sequence<64; ++sequence) llama_memory_seq_rm(memory, sequence, -1, -1);
    const int count = llama_vocab_n_tokens(vocab);
    if (tokens != cached) {
        size_t common = 0;
        while (common < cached.size() && common < tokens.size() && cached[common] == tokens[common]) ++common;
        common = std::min(common, tokens.size() - 1);
        llama_memory_seq_rm(memory, 0, common, -1);
        // Only the first `common` tokens remain valid if decoding throws below.
        cached.resize(common);
        decode(std::vector<llama_token>(tokens.begin() + common, tokens.end()), common, 0);
        const float *logits = llama_get_logits_ith(ctx, -1);
        cachedLogits.assign(logits, logits + count);
        cached = tokens;
        cachedProbabilities.clear(); cachedDiscovery.clear(); discoveryReady = false;
    }
    // Letters within one word share the same sentence context. Candidate decoding
    // changes global logits, so retain an explicit copy of the context distribution.
    const auto &base = cachedLogits;
    if (discover && prefix.empty() && !discoveryReady) {
        cachedDiscovery = discoverWords(base, tokens.size());
        for (const auto &[word, probability] : cachedDiscovery) cachedProbabilities[word] = probability;
        discoveryReady = true;
    }
    const auto generated = discover && prefix.empty() ? cachedDiscovery :
        std::vector<std::pair<std::string, double>>{};
    std::vector<std::pair<std::string, llama_token>> choices;
    for (const auto &word : capitalContext ? capitalWords : completeWords) if (prefix.empty() || word.first.compare(0,prefix.size(),prefix)==0) choices.push_back(word);
    std::partial_sort(choices.begin(), choices.begin()+std::min<size_t>(16,choices.size()), choices.end(),
        [&](const auto &a,const auto &b) { return base[a.second]>base[b.second]; });
    if (choices.size()>16) choices.resize(16);
    std::set<std::string> words;
    for (const auto &word : candidates) words.insert(word);
    for (const auto &word : choices) words.insert(word.first);
    struct Branch { std::string word; std::vector<llama_token> tokens; double score; int sequence; };
    // Word-start scores omit P(word ends here): measured on held-out chat text it moved
    // top-3 by under one point, while costing a model step for every candidate.
    std::map<std::string, double> wholeWords(generated.begin(), generated.end());
    std::vector<Branch> branches;
    for (const auto &word : words) {
        auto existing = cachedProbabilities.find(word);
        if (existing != cachedProbabilities.end()) { wholeWords[word] = existing->second; continue; }
        auto wt = tokenize(surface(word));
        if (wt.empty() || wt.size()>6 || branches.size()>=48) continue;
        int seq = branches.size()+1;
        llama_memory_seq_cp(memory, 0, seq, -1, -1);
        branches.push_back({word, wt, logProbability(base.data(), count, wt[0]), seq});
    }
    // All candidate branches share cached sentence tokens. Score subsequent
    // subwords in one batch per step, producing likelihoods for WHOLE words.
    for (size_t step=1; step<6; ++step) {
        auto batch = llama_batch_init(branches.size(), 0, 1);
        batch.n_tokens = 0;
        std::vector<size_t> active;
        for (size_t b=0; b<branches.size(); ++b) if (branches[b].tokens.size()>step) {
            int i=batch.n_tokens++;
            batch.token[i]=branches[b].tokens[step-1]; batch.pos[i]=tokens.size()+step-1;
            batch.n_seq_id[i]=1; batch.seq_id[i][0]=branches[b].sequence; batch.logits[i]=true;
            active.push_back(b);
        }
        if (active.empty()) { llama_batch_free(batch); break; }
        int status=llama_decode(ctx,batch); llama_batch_free(batch);
        if (status != 0) throw std::runtime_error("Candidate decode failure");
        for (size_t i=0;i<active.size();++i) {
            auto &branch=branches[active[i]];
            branch.score += logProbability(llama_get_logits_ith(ctx,i),count,branch.tokens[step]);
        }
    }
    for (auto &branch:branches) {
        wholeWords[branch.word] = branch.score;
        cachedProbabilities[branch.word] = branch.score;
        llama_memory_seq_rm(memory,branch.sequence,-1,-1);
    }
    // The cache is per current sentence context, never personal-history storage.
    if (cachedProbabilities.size() > 512) cachedProbabilities.clear();
    std::vector<std::pair<std::string,double>> output(wholeWords.begin(), wholeWords.end());
    std::sort(output.begin(),output.end(),[](const auto&a,const auto&b){return a.second>b.second;});
    return output;
}

// Vocabulary-constrained beam search discovers complete multi-token words that
// were absent from the dictionary shortlist. Fixed width/depth bound inference.
std::vector<std::pair<std::string, double>> KeyboardModel::discoverWords(const std::vector<float> &base, int position) {
    const auto &tree = capitalContext ? capitalTree : wordTree;
    if (tree.size() <= 1) return {};
    constexpr int width = 8;
    const int count = llama_vocab_n_tokens(vocab);
    auto memory = llama_get_memory(ctx);
    struct State { size_t node; llama_token token; double score; int parent; int sequence; };
    std::vector<State> frontier;
    std::map<std::string, double> completed;
    auto expand = [&](size_t node, const float *logits, double score, int parent, std::vector<State> &next) {
        const float maximum = *std::max_element(logits, logits + count);
        double total = 0;
        for (int i = 0; i < count; ++i) total += std::exp(logits[i] - maximum);
        const double norm = maximum + std::log(total);
        for (const auto &[token, child] : tree[node].children) {
            double likelihood = score + logits[token] - norm;
            const auto &word = tree[child].word;
            if (!word.empty()) completed[word] = likelihood;
            if (!tree[child].children.empty()) next.push_back({child, token, likelihood, parent, 0});
        }
    };
    expand(0, base.data(), 0, 0, frontier);
    // Word-start vocabularies hold most words in one to three tokens; deeper steps only cost time.
    const int maxDepth = wordStart ? 3 : 6;
    for (int depth = 1; depth < maxDepth && !frontier.empty(); ++depth) {
        if (completed.size() >= 24) {
            std::vector<double> probabilities;
            probabilities.reserve(completed.size());
            for (const auto &[word, probability] : completed) probabilities.push_back(probability);
            std::nth_element(probabilities.begin(), probabilities.begin() + 23, probabilities.end(), std::greater<double>());
            const double cutoff = probabilities[23];
            // A continuation can only reduce log probability. These branches
            // cannot enter the returned top 24, even if their next token is certain.
            frontier.erase(std::remove_if(frontier.begin(), frontier.end(),
                [&](const auto &state) { return state.score < cutoff; }), frontier.end());
            if (frontier.empty()) break;
        }
        std::sort(frontier.begin(), frontier.end(), [](const auto &a, const auto &b) {
            return a.score == b.score ? a.node < b.node : a.score > b.score;
        });
        if (frontier.size() > width) frontier.resize(width);
        const int bank = depth % 2 ? 1 : width + 1;
        auto batch = llama_batch_init(frontier.size(), 0, 1);
        batch.n_tokens = frontier.size();
        for (size_t i = 0; i < frontier.size(); ++i) {
            auto &state = frontier[i];
            state.sequence = bank + i;
            llama_memory_seq_rm(memory, state.sequence, -1, -1);
            llama_memory_seq_cp(memory, state.parent, state.sequence, -1, -1);
            batch.token[i] = state.token; batch.pos[i] = position + depth - 1;
            batch.n_seq_id[i] = 1; batch.seq_id[i][0] = state.sequence; batch.logits[i] = true;
        }
        int status = llama_decode(ctx, batch); llama_batch_free(batch);
        if (status != 0) throw std::runtime_error("Word discovery decode failure");
        std::vector<State> next;
        for (size_t i = 0; i < frontier.size(); ++i) {
            const auto &state = frontier[i];
            expand(state.node, llama_get_logits_ith(ctx, i), state.score, state.sequence, next);
        }
        frontier = std::move(next);
    }
    for (int seq = 1; seq <= width * 2; ++seq) llama_memory_seq_rm(memory, seq, -1, -1);
    std::vector<std::pair<std::string, double>> result(completed.begin(), completed.end());
    std::sort(result.begin(), result.end(), [](const auto &a, const auto &b) { return a.second > b.second; });
    if (result.size() > 24) result.resize(24);
    return result;
}
