#include "prediction.h"
#include <chrono>
#include <cstdlib>
#include <fstream>
#include <iostream>
#include <sstream>
int main(int argc,char **argv) {
    if (argc<2 || argc>4) { std::cerr<<"usage: nboard_prediction_bench model.gguf [--tokens | --words words.txt] < cases.tsv\n"; return 1; }
    try {
        std::vector<std::string> words;
        bool tokensOnly = argc>=3 && std::string(argv[2])=="--tokens";
        if (argc==4 && std::string(argv[2])=="--words") {
            // Dictionary words enable whole-word discovery, as in the app.
            std::ifstream in(argv[3]); std::string entry;
            while (std::getline(in,entry) && words.size()<50000) words.push_back(entry.substr(0,entry.find(' ')));
        }
        llama_log_set([](ggml_log_level, const char *, void *) {}, nullptr);
        KeyboardModel model(argv[1], words);
        std::string line;
        while (std::getline(std::cin,line)) {
            if (tokensOnly) {
                auto tokens=model.tokenize(line,true);
                for (size_t i=0;i<tokens.size();++i) std::cout<<(i?",":"")<<tokens[i];
                std::cout<<"\n"; continue;
            }
            std::istringstream input(line); std::string context,prefix,expected;
            std::getline(input,context,'\t'); std::getline(input,prefix,'\t'); std::getline(input,expected,'\t');
            auto start=std::chrono::steady_clock::now();
            // NBOARD_BENCH_NO_HINT: the engine must find the word itself, as in the app.
            auto scores=std::getenv("NBOARD_BENCH_NO_HINT") ? model.score(context,prefix,{}) : model.score(context,prefix,{expected});
            auto elapsed=std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-start).count();
            std::cout<<elapsed<<"\t"<<expected;
            for (size_t i=0;i<std::min<size_t>(3,scores.size());++i) std::cout<<"\t"<<scores[i].first<<":"<<scores[i].second;
            std::cout<<"\n";
        }
    } catch (const std::exception&e) { std::cerr<<e.what()<<"\n"; return 2; }
}
