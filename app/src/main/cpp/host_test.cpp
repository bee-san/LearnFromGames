// SPDX-License-Identifier: GPL-3.0-or-later
// Runs the JNI-free boundary on the build machine:
//   lfg_dict_host import <zip> <output_dir>
//   lfg_dict_host lookup <dictionary_dir> <text>
//   lfg_dict_host bench <directory of dictionaries> [max_results]
//   lfg_dict_host all <directory of dictionaries> <text>   (the lookup, then the styles, from all of them)
//   lfg_dict_host lang <pack directory> <dictionary_dir> <text>   (a lookup in another language, by its pack)
#include <algorithm>
#include <chrono>
#include <cstdlib>
#include <cstdio>
#include <filesystem>
#include <fstream>
#include <sstream>
#include <string>
#include <vector>

#include "lfg_dict.h"

namespace {

// Lines of the kind games show, looked up from every character as a tap or the controller cursor would.
const char* const kLines[] = {
    "昨日の夜、森の奥で不思議な光を見たんだ。",
    "「お前、まだそんなことを言ってるのか？もう諦めろよ。」",
    "勇者よ、魔王を倒すために旅立つのだ！",
    "このアイテムを使いますか？　はい　いいえ",
    "彼女は静かに微笑んで、何も言わずに部屋を出ていった。",
};

double ms_since(std::chrono::steady_clock::time_point t) {
  return std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t).count();
}

// Byte offsets where each UTF-8 character starts.
std::vector<size_t> starts(const std::string& s) {
  std::vector<size_t> out;
  for (size_t i = 0; i < s.size(); ++i)
    if ((static_cast<unsigned char>(s[i]) & 0xC0) != 0x80) out.push_back(i);
  return out;
}

// Opens every dictionary in `dir` for every kind it has, as the app does. Returns how many.
size_t open_all(lfg::Session& session, const std::string& dir) {
  std::vector<std::string> dicts;
  for (const auto& e : std::filesystem::directory_iterator(dir))
    if (e.is_directory()) dicts.push_back(e.path().string());
  std::sort(dicts.begin(), dicts.end());
  for (const auto& d : dicts)
    for (auto kind : {lfg::DictKind::Term, lfg::DictKind::Frequency, lfg::DictKind::Pitch, lfg::DictKind::Kanji})
      session.add_dictionary(d, kind);
  return dicts.size();
}

int bench(const std::string& dir, int max_results) {
  auto t = std::chrono::steady_clock::now();
  lfg::Session session;
  const size_t count = open_all(session, dir);
  std::printf("open %zu dictionaries: %.1f ms\n", count, ms_since(t));

  std::vector<double> times;
  size_t bytes = 0, worst_bytes = 0;
  for (int pass = 0; pass < 3; ++pass) {
    for (const char* line : kLines) {
      const std::string text = line;
      for (size_t at : starts(text)) {
        const auto t0 = std::chrono::steady_clock::now();
        const std::string out = session.lookup(text.substr(at), max_results, 16);
        if (pass > 0) {  // the first pass warms the page cache
          times.push_back(ms_since(t0));
          bytes += out.size();
          worst_bytes = std::max(worst_bytes, out.size());
        }
      }
    }
  }
  std::sort(times.begin(), times.end());
  double sum = 0;
  for (double x : times) sum += x;
  std::printf("lookup (max %d): %zu lookups, mean %.2f ms, median %.2f ms, p95 %.2f ms, max %.2f ms\n", max_results,
              times.size(), sum / times.size(), times[times.size() / 2], times[times.size() * 95 / 100], times.back());
  std::printf("answer size: mean %zu KB, max %zu KB\n", bytes / times.size() / 1024, worst_bytes / 1024);

  // A controller word pass over every line, as the cursor's segmenting does.
  times.clear();
  for (int pass = 0; pass < 3; ++pass) {
    for (const char* line : kLines) {
      const std::string text = line;
      const auto t0 = std::chrono::steady_clock::now();
      for (size_t at : starts(text)) session.match_length(text.substr(at), 16);
      if (pass > 0) times.push_back(ms_since(t0));
    }
  }
  sum = 0;
  for (double x : times) sum += x;
  std::printf("match length, every start of a line: mean %.2f ms a line\n", sum / times.size());

  // It must agree with the full lookup's first match, measured in UTF-16 units as Kotlin counts.
  size_t agree = 0, total = 0;
  for (const char* line : kLines) {
    const std::string text = line;
    for (size_t at : starts(text)) {
      const std::string json = session.lookup(text.substr(at), 8, 16);  // as the app looked up before
      int expected = 0;
      const size_t m = json.find("\"matched\":\"");
      if (m != std::string::npos) {
        const size_t from = m + 11, to = json.find('"', from);
        for (size_t i = from; i < to; ++i) {
          const unsigned char c = json[i];
          if ((c & 0xC0) != 0x80) expected += c >= 0xF0 ? 2 : 1;
        }
      }
      ++total;
      if (session.match_length(text.substr(at), 16) == expected) ++agree;
    }
  }
  std::printf("match length agrees with lookup: %zu of %zu\n", agree, total);
  return 0;
}

}  // namespace

int main(int argc, char** argv) {
  if (argc >= 4 && std::string(argv[1]) == "all") {  // a lookup and the styles from every dictionary in a directory
    lfg::Session session;
    open_all(session, argv[2]);
    std::puts(session.lookup(argv[3], 8, 16).c_str());
    std::puts(session.styles().c_str());
    return 0;
  }
  if (argc >= 3 && std::string(argv[1]) == "bench") return bench(argv[2], argc > 3 ? std::atoi(argv[3]) : 8);
  if (argc < 4) {
    std::fprintf(stderr, "usage: %s import <zip> <output_dir> | lookup <dictionary_dir> <text> | bench <dictionaries_dir> [max_results]\n", argv[0]);
    return 2;
  }
  const std::string mode = argv[1];
  if (mode == "import") {
    std::puts(lfg::import_dictionary(argv[2], argv[3], false).c_str());
    return 0;
  }
  if (mode == "lang" && argc >= 5) {
    // A pack is a directory named by its language's code, as third_party/hoshidicts/languages/es.
    const std::filesystem::path pack = argv[2];
    auto slurp = [](const std::filesystem::path& file) {
      std::ifstream in(file, std::ios::binary);
      std::ostringstream out;
      out << in.rdbuf();
      return out.str();
    };
    lfg::Session session;
    const std::string error = session.add_language(slurp(pack / "language.json"), slurp(pack / "transforms.json"));
    if (!error.empty()) {
      std::fprintf(stderr, "%s\n", error.c_str());
      return 1;
    }
    if (!session.add_dictionary(argv[3], lfg::DictKind::Term)) {
      std::fprintf(stderr, "could not open %s\n", argv[3]);
      return 1;
    }
    std::string code = pack.filename().string();
    if (code.empty()) code = pack.parent_path().filename().string();
    std::puts(session.lookup(argv[4], 8, 16, code).c_str());
    return 0;
  }
  if (mode == "lookup") {
    lfg::Session session;
    if (!session.ok() || !session.add_dictionary(argv[2], lfg::DictKind::Term)) {
      std::fprintf(stderr, "could not open %s\n", argv[2]);
      return 1;
    }
    // Its frequencies and pitch accents too; a dictionary without them is refused for those, which is fine here.
    session.add_dictionary(argv[2], lfg::DictKind::Frequency);
    session.add_dictionary(argv[2], lfg::DictKind::Pitch);
    std::puts(session.lookup(argv[3], 8, 16).c_str());
    return 0;
  }
  return 2;
}
