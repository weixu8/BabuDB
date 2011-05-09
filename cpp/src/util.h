// Copyright (c) 2008, Felix Hupfeld, Jan Stender, Bjoern Kolbeck, Mikael Hoegqvist, Zuse Institute Berlin.
// Licensed under the BSD License, see LICENSE file for details.

#ifndef TIL_H
#define TIL_H

#include <string>

namespace yield { class Path; }

namespace babudb {
bool matchFilename(
    const yield::Path& fullpath, const std::string& desired_prefix,
    const std::string& desired_ext, unsigned int& no);
};

#endif
