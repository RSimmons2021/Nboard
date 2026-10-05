#include "prediction.h"
#include <algorithm>
#include <cmath>
#include <cstring>
#include <stdexcept>
#include <mutex>
#include <set>

namespace {
double logProbability(const float *logits, int count, int token) {
    float maximum = *std::max_element(logits, logits + count);
    double total = 0;
    for (int i = 0; i < count; ++i) total += std::exp(logits[i] - maximum);
    return logits[token] - maximum - std::log(total);
}
bool isWord(const std::string &word) {
    if (word.empty() || word.size() > 48) return false;
    for (unsigned char c : word) if (!(c >= 128 || (c >= 'a' && c <= 'z') || c == '\'')) return false;
    return true;
}
}

KeyboardModel::KeyboardModel(const std::string &path) {
    static std::once_flag initialized;
    std::call_once(initialized, [] { llama_backend_init(); });
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
    cp.n_ctx = 512; cp.n_batch = 128; cp.n_ubatch = 128; cp.n_seq_max = 64;
    cp.n_threads = 2; cp.n_threads_batch = 2; cp.kv_unified = true;
    ctx = llama_init_from_model(model, cp);
    if (!ctx) { llama_model_free(model); model = nullptr; throw std::runtime_error("Failed to create model context"); }
    // Only complete word tokens are offered; subword fragments never reach the UI.
    for (int t = 0; t < llama_vocab_n_tokens(vocab); ++t) {
        std::string raw = llama_vocab_get_text(vocab, t);
        const std::string space = "\xe2\x96\x81";
        if (raw.size() > space.size() && raw.compare(raw.size()-space.size(), space.size(), space)==0) {
            auto word = raw.substr(0, raw.size()-space.size());
            if (isWord(word)) completeWords.emplace_back(word, t);
        }
    }
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
        const std::string &prefix, const std::vector<std::string> &candidates) {
    auto tokens = tokenize(context, true);
    if (tokens.size() > 128) tokens.erase(tokens.begin(), tokens.end()-128);
    if (tokens.empty()) return {};
    auto memory = llama_get_memory(ctx);
    for (int sequence=1; sequence<64; ++sequence) llama_memory_seq_rm(memory, sequence, -1, -1);
    size_t common = 0;
    while (common < cached.size() && common < tokens.size() && cached[common]==tokens[common]) ++common;
    // Refresh the final logits even when the entire context is cached.
    common = std::min(common, tokens.size()-1);
    llama_memory_seq_rm(memory, 0, common, -1);
    decode(std::vector<llama_token>(tokens.begin()+common, tokens.end()), common, 0);
    cached = tokens;
    const int count = llama_vocab_n_tokens(vocab);
    const float *logits = llama_get_logits_ith(ctx, -1);
    std::vector<float> base(logits, logits+count);
    std::vector<std::pair<std::string, llama_token>> choices;
    for (const auto &word : completeWords) if (prefix.empty() || word.first.compare(0,prefix.size(),prefix)==0) choices.push_back(word);
    std::partial_sort(choices.begin(), choices.begin()+std::min<size_t>(16,choices.size()), choices.end(),
        [&](const auto &a,const auto &b) { return base[a.second]>base[b.second]; });
    if (choices.size()>16) choices.resize(16);
    std::set<std::string> words;
    for (const auto &word : candidates) words.insert(word);
    for (const auto &word : choices) words.insert(word.first);
    struct Branch { std::string word; std::vector<llama_token> tokens; double score; int sequence; };
    std::vector<Branch> branches;
    for (const auto &word : words) {
        auto wt = tokenize(word+" ");
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
            branch.score+=logProbability(llama_get_logits_ith(ctx,i),count,branch.tokens[step]);
        }
    }
    std::vector<std::pair<std::string,double>> output;
    for (auto &branch:branches) {
        output.emplace_back(branch.word,branch.score);
        llama_memory_seq_rm(memory,branch.sequence,-1,-1);
    }
    std::sort(output.begin(),output.end(),[](const auto&a,const auto&b){return a.second>b.second;});
    return output;
}
