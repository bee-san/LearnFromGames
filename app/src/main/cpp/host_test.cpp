// SPDX-License-Identifier: GPL-3.0-or-later
// Runs the JNI-free boundary on the build machine:
//   lfg_dict_host import <zip> <output_dir>
//   lfg_dict_host lookup <dictionary_dir> <text>
#include <cstdio>
#include <string>

#include "lfg_dict.h"

int main(int argc, char** argv) {
  if (argc < 4) {
    std::fprintf(stderr, "usage: %s import <zip> <output_dir> | lookup <dictionary_dir> <text>\n", argv[0]);
    return 2;
  }
  const std::string mode = argv[1];
  if (mode == "import") {
    std::puts(lfg::import_dictionary(argv[2], argv[3], false).c_str());
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
