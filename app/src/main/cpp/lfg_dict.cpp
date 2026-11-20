// SPDX-License-Identifier: GPL-3.0-or-later
#include "lfg_dict.h"

#include <cstdio>
#include <string_view>

#include "hoshidicts_c.h"

namespace lfg {
namespace {

void append_escaped(std::string& out, std::string_view s) {
  out.push_back('"');
  for (unsigned char c : s) {
    switch (c) {
      case '"': out += "\\\""; break;
      case '\\': out += "\\\\"; break;
      case '\n': out += "\\n"; break;
      case '\r': out += "\\r"; break;
      case '\t': out += "\\t"; break;
      default:
        if (c < 0x20) {
          char buf[8];
          std::snprintf(buf, sizeof buf, "\\u%04x", c);
          out += buf;
        } else {
          out.push_back(static_cast<char>(c));
        }
    }
  }
  out.push_back('"');
}

std::string_view view(const hd_str& s) { return s.ptr == nullptr ? std::string_view{} : std::string_view{s.ptr, s.len}; }

void key(std::string& out, const char* name) {
  out.push_back('"');
  out += name;
  out += "\":";
}

void field(std::string& out, const char* name, std::string_view value, bool comma = true) {
  key(out, name);
  append_escaped(out, value);
  if (comma) out.push_back(',');
}

void number(std::string& out, const char* name, long long value, bool comma = true) {
  key(out, name);
  out += std::to_string(value);
  if (comma) out.push_back(',');
}

void append_term(std::string& out, const hd_lookup_result& r) {
  out.push_back('{');
  field(out, "matched", view(r.matched));
  field(out, "deinflected", view(r.deinflected));
  key(out, "trace");
  out.push_back('[');
  for (size_t i = 0; i < r.trace_count; ++i) {
    if (i) out.push_back(',');
    out.push_back('{');
    field(out, "name", view(r.trace[i].name));
    field(out, "description", view(r.trace[i].description), false);
    out.push_back('}');
  }
  out += "],";
  const hd_term_result& t = r.term;
  field(out, "expression", view(t.expression));
  field(out, "reading", view(t.reading));
  field(out, "rules", view(t.rules));
  number(out, "score", t.score);
  key(out, "glossaries");
  out.push_back('[');
  for (size_t i = 0; i < t.glossaries_count; ++i) {
    if (i) out.push_back(',');
    const hd_glossary_entry& g = t.glossaries[i];
    out.push_back('{');
    field(out, "dictionary", view(g.dict_name));
    field(out, "glossary", view(g.glossary));
    field(out, "definitionTags", view(g.definition_tags));
    field(out, "termTags", view(g.term_tags), false);
    out.push_back('}');
  }
  out += "],";
  key(out, "frequencies");
  out.push_back('[');
  for (size_t i = 0; i < t.frequencies_count; ++i) {
    if (i) out.push_back(',');
    const hd_frequency_entry& f = t.frequencies[i];
    out.push_back('{');
    field(out, "dictionary", view(f.dict_name));
    key(out, "values");
    out.push_back('[');
    for (size_t j = 0; j < f.frequencies_count; ++j) {
      if (j) out.push_back(',');
      out.push_back('{');
      number(out, "value", f.frequencies[j].value);
      field(out, "display", view(f.frequencies[j].display_value), false);
      out.push_back('}');
    }
    out += "]}";
  }
  out += "],";
  key(out, "pitches");
  out.push_back('[');
  for (size_t i = 0; i < t.pitches_count; ++i) {
    if (i) out.push_back(',');
    const hd_pitch_entry& p = t.pitches[i];
    out.push_back('{');
    field(out, "dictionary", view(p.dict_name));
    key(out, "values");
    out.push_back('[');
    for (size_t j = 0; j < p.pitches_count; ++j) {
      if (j) out.push_back(',');
      out.push_back('{');
      number(out, "position", p.pitches[j].position);
      field(out, "pattern", view(p.pitches[j].pattern), false);
      out.push_back('}');
    }
    out += "]}";
  }
  out += "]}";
}

}  // namespace

std::string import_dictionary(const std::string& zip_path, const std::string& output_dir, bool low_ram) {
  std::string out = "{";
  hd_import_result* r = hd_import(zip_path.c_str(), output_dir.c_str(), low_ram ? 1 : 0);
  if (r == nullptr) {
    out += "\"success\":false,";
    field(out, "error", "The dictionary importer failed.", false);
    out.push_back('}');
    return out;
  }
  out += hd_import_result_success(r) ? "\"success\":true," : "\"success\":false,";
  field(out, "title", hd_import_result_title(r));
  number(out, "terms", static_cast<long long>(hd_import_result_term_count(r)));
  number(out, "frequencies", static_cast<long long>(hd_import_result_freq_count(r)));
  number(out, "pitches", static_cast<long long>(hd_import_result_pitch_count(r)));
  number(out, "kanji", static_cast<long long>(hd_import_result_kanji_count(r)));
  number(out, "media", static_cast<long long>(hd_import_result_media_count(r)));
  field(out, "error", hd_import_result_error(r), false);
  out.push_back('}');
  hd_import_result_free(r);
  return out;
}

Session::Session() {
  query_ = hd_query_new();
  deinflector_ = hd_deinflector_new();
  if (query_ != nullptr && deinflector_ != nullptr) lookup_ = hd_lookup_new(query_, deinflector_);
}

Session::~Session() {
  if (lookup_ != nullptr) hd_lookup_free(lookup_);
  if (deinflector_ != nullptr) hd_deinflector_free(deinflector_);
  if (query_ != nullptr) hd_query_free(query_);
}

bool Session::add_dictionary(const std::string& path, DictKind kind) {
  if (query_ == nullptr) return false;
  switch (kind) {
    case DictKind::Term: return hd_query_add_term_dict(query_, path.c_str()) == 0;  // 0 is success
    case DictKind::Frequency: return hd_query_add_freq_dict(query_, path.c_str()) == 0;  // 0 is success
    case DictKind::Pitch: return hd_query_add_pitch_dict(query_, path.c_str()) == 0;  // 0 is success
    case DictKind::Kanji: return hd_query_add_kanji_dict(query_, path.c_str()) == 0;  // 0 is success
  }
  return false;
}

std::string Session::lookup(const std::string& text, int max_results, size_t scan_length) const {
  std::string out = "{\"results\":[";
  if (lookup_ != nullptr && !text.empty()) {
    const hd_lookup_result* results = nullptr;
    size_t count = 0;
    hd_lookup_results* handle = hd_lookup_run(lookup_, text.c_str(), max_results, scan_length, &results, &count);
    if (handle != nullptr) {
      for (size_t i = 0; i < count; ++i) {
        if (i) out.push_back(',');
        append_term(out, results[i]);
      }
      hd_lookup_results_free(handle);
    }
  }
  out += "]}";
  return out;
}

int Session::match_length(const std::string& text, size_t scan_length) const {
  if (lookup_ == nullptr || text.empty()) return 0;
  const hd_lookup_result* results = nullptr;
  size_t count = 0;
  hd_lookup_results* handle = hd_lookup_run(lookup_, text.c_str(), 1, scan_length, &results, &count);
  if (handle == nullptr) return 0;
  int units = 0;
  if (count > 0) {
    // UTF-8 to UTF-16 units: a four-byte character is a surrogate pair, every other is one unit.
    for (unsigned char c : view(results[0].matched)) {
      if ((c & 0xC0) == 0x80) continue;
      units += (c >= 0xF0) ? 2 : 1;
    }
  }
  hd_lookup_results_free(handle);
  return units;
}

std::string Session::styles() const {
  std::string out = "[";
  if (query_ != nullptr) {
    const hd_dictionary_style* styles = nullptr;
    size_t count = 0;
    hd_styles* handle = hd_query_get_styles(query_, &styles, &count);
    if (handle != nullptr) {
      for (size_t i = 0; i < count; ++i) {
        if (i) out.push_back(',');
        out.push_back('{');
        field(out, "dictionary", view(styles[i].dict_name));
        field(out, "css", view(styles[i].styles), false);
        out.push_back('}');
      }
      hd_styles_free(handle);
    }
  }
  out.push_back(']');
  return out;
}

std::string Session::media(const std::string& dictionary, const std::string& path) const {
  if (query_ == nullptr) return {};
  const hd_media_file file = hd_query_get_media_file(query_, dictionary.c_str(), path.c_str());
  if (file.data == nullptr || file.size == 0) return {};
  return std::string(reinterpret_cast<const char*>(file.data), file.size);
}

}  // namespace lfg
