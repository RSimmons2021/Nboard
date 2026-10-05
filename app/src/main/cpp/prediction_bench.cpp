#include "prediction.h"
#include <chrono>
#include <iostream>
#include <sstream>
int main(int argc,char **argv) {
    if (argc<2 || argc>3) { std::cerr<<"usage: nboard_prediction_bench model.gguf [--tokens] < cases.tsv\n"; return 1; }
    try {
        KeyboardModel model(argv[1]);
        std::string line;
        while (std::getline(std::cin,line)) {
            if (argc==3 && std::string(argv[2])=="--tokens") {
                auto tokens=model.tokenize(line,true);
                for (size_t i=0;i<tokens.size();++i) std::cout<<(i?",":"")<<tokens[i];
                std::cout<<"\n"; continue;
            }
            std::istringstream input(line); std::string context,prefix,expected;
            std::getline(input,context,'\t'); std::getline(input,prefix,'\t'); std::getline(input,expected,'\t');
            auto start=std::chrono::steady_clock::now();
            auto scores=model.score(context,prefix,{expected});
            auto elapsed=std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-start).count();
            std::cout<<elapsed<<"\t"<<expected;
            for (size_t i=0;i<std::min<size_t>(3,scores.size());++i) std::cout<<"\t"<<scores[i].first<<":"<<scores[i].second;
            std::cout<<"\n";
        }
    } catch (const std::exception&e) { std::cerr<<e.what()<<"\n"; return 2; }
}
