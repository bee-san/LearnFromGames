// SPDX-License-Identifier: GPL-3.0-or-later
// The boundary to hoshidicts. Everything crosses it as UTF-8 JSON, the way
// Hachidori's WebAssembly build does, so the Kotlin side never mirrors structs.
#pragma once

#include <string>

struct hd_query;
struct hd_deinflector;
struct hd_lookup;

namespace lfg {

// Which index of an imported dictionary to attach to a session.
enum class DictKind : int { Term = 0, Frequency = 1, Pitch = 2, Kanji = 3 };

// Imports a Yomitan zip into `output_dir`. Returns
// {"success":bool,"title":str,"terms":n,"frequencies":n,"pitches":n,"kanji":n,"media":n,"error":str}.
std::string import_dictionary(const std::string& zip_path, const std::string& output_dir, bool low_ram);

class Session {
 public:
  Session();
  ~Session();
  Session(const Session&) = delete;
  Session& operator=(const Session&) = delete;

  bool ok() const { return lookup_ != nullptr; }
  bool add_dictionary(const std::string& path, DictKind kind);

  // Longest-match lookup from the first character of `text`. Returns
  // {"results":[{"matched","deinflected","trace":[{"name","description"}],
  //   "expression","reading","rules","score",
  //   "glossaries":[{"dictionary","glossary","definitionTags","termTags"}],
  //   "frequencies":[{"dictionary","values":[{"value","display"}]}]}]}.
  // `glossary` is the raw Yomitan glossary JSON, carried as a string.
  std::string lookup(const std::string& text, int max_results, size_t scan_length) const;

  // Each dictionary's own stylesheet: [{"dictionary","css"}].
  std::string styles() const;

  // A picture or other file a dictionary ships, or empty when there is none.
  std::string media(const std::string& dictionary, const std::string& path) const;

 private:
  hd_query* query_ = nullptr;
  hd_deinflector* deinflector_ = nullptr;
  hd_lookup* lookup_ = nullptr;
};

}  // namespace lfg
