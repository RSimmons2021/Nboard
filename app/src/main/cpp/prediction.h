#pragma once
#include "llama.h"
#include <string>
#include <vector>
#include <utility>

class KeyboardModel {
public:
    explicit KeyboardModel(const std::string &path);
    ~KeyboardModel();
    std::vector<std::pair<std::string, double>> score(const std::string &context, const std::string &prefix,
                                                    const std::vector<std::string> &candidates);
    std::vector<llama_token> tokenize(const std::string &text, bool bos = false);
private:
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    const llama_vocab *vocab = nullptr;
    std::vector<llama_token> cached;
    std::vector<std::pair<std::string, llama_token>> completeWords;
    void decode(const std::vector<llama_token> &tokens, int position, int sequence);
};
