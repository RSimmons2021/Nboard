#pragma once
#include "llama.h"
#include <string>
#include <vector>
#include <utility>
#include <map>

class KeyboardModel {
public:
    explicit KeyboardModel(const std::string &path, const std::vector<std::string> &words = {});
    ~KeyboardModel();
    std::vector<std::pair<std::string, double>> score(const std::string &context, const std::string &prefix,
                                                    const std::vector<std::string> &candidates, bool discover = true);
    std::vector<llama_token> tokenize(const std::string &text, bool bos = false);
    /** True for tokenizers that mark word starts (" word"); false for word-end markers ("word▁"). */
    bool isWordStart() const { return wordStart; }
private:
    bool wordStart = false;
    std::vector<std::pair<std::string, llama_token>> capitalWords;
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    const llama_vocab *vocab = nullptr;
    std::vector<llama_token> cached;
    std::vector<float> cachedLogits;
    std::map<std::string, double> cachedProbabilities;
    std::vector<std::pair<std::string, double>> cachedDiscovery;
    bool discoveryReady = false;
    std::vector<std::pair<std::string, llama_token>> completeWords;
    struct WordNode { std::map<llama_token, size_t> children; std::string word; };
    std::vector<WordNode> wordTree = {WordNode{}};
    std::vector<WordNode> capitalTree = {WordNode{}};  // word-start mode, sentence-initial forms
    bool capitalContext = false;
    void addToTree(std::vector<WordNode> &tree, const std::vector<llama_token> &pieces, const std::string &word);
    std::string surface(const std::string &word) const;
    std::vector<std::pair<std::string, double>> discoverWords(const std::vector<float> &base, int position);
    void decode(const std::vector<llama_token> &tokens, int position, int sequence);
};
