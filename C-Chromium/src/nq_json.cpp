#include "nq_json.h"

#include <cmath>
#include <cstdlib>
#include <cstring>
#include <set>

namespace nq {
namespace json {

const Value* Value::Find(const std::string& key) const {
  for (const auto& kv : members_) {
    if (kv.first == key) return &kv.second;
  }
  return nullptr;
}

void Value::set_bool(bool v) {
  type_ = Type::kBool;
  bool_value_ = v;
}

void Value::set_number(double v) {
  type_ = Type::kNumber;
  number_value_ = v;
}

void Value::set_string(std::string v) {
  type_ = Type::kString;
  string_value_ = std::move(v);
}

void Value::AddMember(std::string key, Value value) {
  type_ = Type::kObject;
  members_.emplace_back(std::move(key), std::move(value));
}

void Value::AddElement(Value value) {
  type_ = Type::kArray;
  elements_.push_back(std::move(value));
}

namespace {

class Parser {
 public:
  Parser(const std::string& text, const Limit& limit)
      : text_(text), limit_(limit) {}

  ParseResult Run() {
    ParseResult result;
    SkipWhitespace();
    Value root;
    if (!ParseValue(&root, 0)) {
      result.ok = false;
      result.error = error_;
      result.error_offset = error_offset_;
      return result;
    }
    SkipWhitespace();
    if (pos_ != text_.size()) {
      return Fail("ada byte tambahan setelah nilai JSON pertama");
    }
    result.ok = true;
    result.value = std::move(root);
    return result;
  }

 private:
  bool AtEnd() const { return pos_ >= text_.size(); }
  char Peek() const { return text_[pos_]; }

  ParseResult Fail(const std::string& message) {
    error_ = message;
    error_offset_ = pos_;
    ParseResult r;
    r.ok = false;
    r.error = message;
    r.error_offset = pos_;
    return r;
  }

  void SkipWhitespace() {
    while (!AtEnd()) {
      const char c = Peek();
      if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
        ++pos_;
      } else {
        break;
      }
    }
  }

  bool ParseValue(Value* out, std::size_t depth) {
    if (depth > limit_.max_depth) {
      error_ = "kedalaman JSON melebihi batas";
      error_offset_ = pos_;
      return false;
    }
    if (++nodes_ > limit_.max_nodes) {
      error_ = "jumlah simpul JSON melebihi batas";
      error_offset_ = pos_;
      return false;
    }
    if (AtEnd()) {
      error_ = "dokumen terpotong (mengharapkan nilai)";
      error_offset_ = pos_;
      return false;
    }
    switch (Peek()) {
      case '{':
        return ParseObject(out, depth);
      case '[':
        return ParseArray(out, depth);
      case '"': {
        std::string s;
        if (!ParseString(&s)) return false;
        out->set_string(std::move(s));
        return true;
      }
      case 't':
        return ParseLiteral("true", out, Value(true));
      case 'f':
        return ParseLiteral("false", out, Value(false));
      case 'n': {
        Value v;
        return ParseLiteral("null", out, v);
      }
      default:
        return ParseNumber(out);
    }
  }

  bool ParseLiteral(const char* literal, Value* out, Value value) {
    const std::size_t len = std::strlen(literal);
    if (text_.compare(pos_, len, literal) != 0) {
      error_ = std::string("literal tidak dikenal, mengharapkan '") + literal + "'";
      error_offset_ = pos_;
      return false;
    }
    pos_ += len;
    *out = std::move(value);
    return true;
  }

  bool ParseObject(Value* out, std::size_t depth) {
    ++pos_;  // '{'
    Value obj;
    SkipWhitespace();
    if (!AtEnd() && Peek() == '}') {
      ++pos_;
      *out = std::move(obj);
      return true;
    }
    std::set<std::string> seen;
    for (;;) {
      SkipWhitespace();
      if (AtEnd() || Peek() != '"') {
        error_ = "kunci objek harus berupa string";
        error_offset_ = pos_;
        return false;
      }
      std::string key;
      if (!ParseString(&key)) return false;
      // Kunci duplikat ditolak: dua nilai untuk satu kunci membuat kebijakan
      // yang berlaku bergantung pada urutan pembacaan -> tidak dapat diaudit.
      if (!seen.insert(key).second) {
        error_ = "kunci objek duplikat: '" + key + "'";
        error_offset_ = pos_;
        return false;
      }
      SkipWhitespace();
      if (AtEnd() || Peek() != ':') {
        error_ = "mengharapkan ':' setelah kunci objek";
        error_offset_ = pos_;
        return false;
      }
      ++pos_;
      SkipWhitespace();
      Value child;
      if (!ParseValue(&child, depth + 1)) return false;
      obj.AddMember(std::move(key), std::move(child));
      SkipWhitespace();
      if (AtEnd()) {
        error_ = "objek tidak ditutup";
        error_offset_ = pos_;
        return false;
      }
      if (Peek() == ',') {
        ++pos_;
        continue;
      }
      if (Peek() == '}') {
        ++pos_;
        *out = std::move(obj);
        return true;
      }
      error_ = "mengharapkan ',' atau '}' di dalam objek";
      error_offset_ = pos_;
      return false;
    }
  }

  bool ParseArray(Value* out, std::size_t depth) {
    ++pos_;  // '['
    Value arr;
    SkipWhitespace();
    if (!AtEnd() && Peek() == ']') {
      ++pos_;
      *out = std::move(arr);
      return true;
    }
    for (;;) {
      SkipWhitespace();
      Value child;
      if (!ParseValue(&child, depth + 1)) return false;
      arr.AddElement(std::move(child));
      SkipWhitespace();
      if (AtEnd()) {
        error_ = "array tidak ditutup";
        error_offset_ = pos_;
        return false;
      }
      if (Peek() == ',') {
        ++pos_;
        continue;
      }
      if (Peek() == ']') {
        ++pos_;
        *out = std::move(arr);
        return true;
      }
      error_ = "mengharapkan ',' atau ']' di dalam array";
      error_offset_ = pos_;
      return false;
    }
  }

  bool ParseHex4(unsigned* out) {
    if (pos_ + 4 > text_.size()) {
      error_ = "escape \\u terpotong";
      error_offset_ = pos_;
      return false;
    }
    unsigned value = 0;
    for (int i = 0; i < 4; ++i) {
      const char c = text_[pos_ + i];
      int digit;
      if (c >= '0' && c <= '9') {
        digit = c - '0';
      } else if (c >= 'a' && c <= 'f') {
        digit = c - 'a' + 10;
      } else if (c >= 'A' && c <= 'F') {
        digit = c - 'A' + 10;
      } else {
        error_ = "digit hex tidak valid pada escape \\u";
        error_offset_ = pos_ + i;
        return false;
      }
      value = (value << 4) | static_cast<unsigned>(digit);
    }
    pos_ += 4;
    *out = value;
    return true;
  }

  static void AppendUtf8(unsigned code_point, std::string* out) {
    if (code_point <= 0x7F) {
      out->push_back(static_cast<char>(code_point));
    } else if (code_point <= 0x7FF) {
      out->push_back(static_cast<char>(0xC0 | (code_point >> 6)));
      out->push_back(static_cast<char>(0x80 | (code_point & 0x3F)));
    } else if (code_point <= 0xFFFF) {
      out->push_back(static_cast<char>(0xE0 | (code_point >> 12)));
      out->push_back(static_cast<char>(0x80 | ((code_point >> 6) & 0x3F)));
      out->push_back(static_cast<char>(0x80 | (code_point & 0x3F)));
    } else {
      out->push_back(static_cast<char>(0xF0 | (code_point >> 18)));
      out->push_back(static_cast<char>(0x80 | ((code_point >> 12) & 0x3F)));
      out->push_back(static_cast<char>(0x80 | ((code_point >> 6) & 0x3F)));
      out->push_back(static_cast<char>(0x80 | (code_point & 0x3F)));
    }
  }

  bool ParseString(std::string* out) {
    ++pos_;  // '"'
    out->clear();
    for (;;) {
      if (AtEnd()) {
        error_ = "string tidak ditutup";
        error_offset_ = pos_;
        return false;
      }
      const unsigned char c = static_cast<unsigned char>(text_[pos_]);
      if (c == '"') {
        ++pos_;
        return true;
      }
      if (c < 0x20) {
        error_ = "karakter kontrol mentah di dalam string (harus di-escape)";
        error_offset_ = pos_;
        return false;
      }
      if (c != '\\') {
        out->push_back(static_cast<char>(c));
        ++pos_;
        continue;
      }
      ++pos_;  // '\'
      if (AtEnd()) {
        error_ = "escape terpotong";
        error_offset_ = pos_;
        return false;
      }
      const char esc = text_[pos_++];
      switch (esc) {
        case '"': out->push_back('"'); break;
        case '\\': out->push_back('\\'); break;
        case '/': out->push_back('/'); break;
        case 'b': out->push_back('\b'); break;
        case 'f': out->push_back('\f'); break;
        case 'n': out->push_back('\n'); break;
        case 'r': out->push_back('\r'); break;
        case 't': out->push_back('\t'); break;
        case 'u': {
          unsigned cp = 0;
          if (!ParseHex4(&cp)) return false;
          if (cp >= 0xD800 && cp <= 0xDBFF) {
            // High surrogate: wajib diikuti low surrogate.
            if (pos_ + 1 >= text_.size() || text_[pos_] != '\\' ||
                text_[pos_ + 1] != 'u') {
              error_ = "surrogate tinggi tanpa pasangan";
              error_offset_ = pos_;
              return false;
            }
            pos_ += 2;
            unsigned low = 0;
            if (!ParseHex4(&low)) return false;
            if (low < 0xDC00 || low > 0xDFFF) {
              error_ = "surrogate rendah tidak valid";
              error_offset_ = pos_ - 4;
              return false;
            }
            cp = 0x10000 + ((cp - 0xD800) << 10) + (low - 0xDC00);
          } else if (cp >= 0xDC00 && cp <= 0xDFFF) {
            error_ = "surrogate rendah tanpa pasangan";
            error_offset_ = pos_ - 4;
            return false;
          }
          AppendUtf8(cp, out);
          break;
        }
        default:
          error_ = "escape tidak dikenal";
          error_offset_ = pos_ - 1;
          return false;
      }
    }
  }

  bool ParseNumber(Value* out) {
    const std::size_t start = pos_;
    if (!AtEnd() && Peek() == '-') ++pos_;
    if (AtEnd()) {
      return NumberFail("angka tidak lengkap");
    }
    if (Peek() == '0') {
      ++pos_;
      // "01" bukan JSON yang sah.
      if (!AtEnd() && Peek() >= '0' && Peek() <= '9') {
        return NumberFail("angka tidak boleh diawali nol");
      }
    } else if (Peek() >= '1' && Peek() <= '9') {
      while (!AtEnd() && Peek() >= '0' && Peek() <= '9') ++pos_;
    } else {
      return NumberFail("bukan awal nilai JSON yang sah");
    }
    if (!AtEnd() && Peek() == '.') {
      ++pos_;
      if (AtEnd() || Peek() < '0' || Peek() > '9') {
        return NumberFail("pecahan tanpa digit");
      }
      while (!AtEnd() && Peek() >= '0' && Peek() <= '9') ++pos_;
    }
    if (!AtEnd() && (Peek() == 'e' || Peek() == 'E')) {
      ++pos_;
      if (!AtEnd() && (Peek() == '+' || Peek() == '-')) ++pos_;
      if (AtEnd() || Peek() < '0' || Peek() > '9') {
        return NumberFail("eksponen tanpa digit");
      }
      while (!AtEnd() && Peek() >= '0' && Peek() <= '9') ++pos_;
    }
    const std::string token = text_.substr(start, pos_ - start);
    const double value = std::strtod(token.c_str(), nullptr);
    if (!std::isfinite(value)) {
      return NumberFail("angka bukan nilai terbatas");
    }
    out->set_number(value);
    return true;
  }

  bool NumberFail(const std::string& message) {
    error_ = message;
    error_offset_ = pos_;
    return false;
  }

  const std::string& text_;
  Limit limit_;
  std::size_t pos_ = 0;
  std::size_t nodes_ = 0;
  std::string error_;
  std::size_t error_offset_ = 0;
};

}  // namespace

ParseResult Parse(const std::string& text, const Limit& limit) {
  Parser parser(text, limit);
  return parser.Run();
}

}  // namespace json
}  // namespace nq
