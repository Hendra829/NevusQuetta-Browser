#include "nq_jcs.h"

#include <algorithm>
#include <charconv>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <string>
#include <vector>

#include "nq_json.h"

namespace nq {
namespace jcs {
namespace {

// ---------------------------------------------------------------------------
// UTF-8
// ---------------------------------------------------------------------------

std::string HexByte(unsigned char b) {
  static const char* kHex = "0123456789abcdef";
  std::string s = "0x";
  s.push_back(kHex[(b >> 4) & 0xF]);
  s.push_back(kHex[b & 0xF]);
  return s;
}

// Mengurai UTF-8 dengan VALIDASI KETAT (RFC 3629): menolak bentuk overlong,
// titik kode surrogate, dan titik kode di luar U+10FFFF. UTF-8 tidak sah ->
// gagal-tertutup, karena byte yang tidak sah dapat membuat dua implementasi
// menyimpulkan dokumen yang berbeda dari berkas yang sama.
bool DecodeUtf8(const std::string& s, std::vector<std::uint32_t>* out,
                std::string* error) {
  out->clear();
  std::size_t i = 0;
  const std::size_t n = s.size();
  while (i < n) {
    const unsigned char b0 = static_cast<unsigned char>(s[i]);
    std::uint32_t cp = 0;
    std::size_t extra = 0;
    if (b0 < 0x80) {
      cp = b0;
      extra = 0;
    } else if ((b0 & 0xE0) == 0xC0) {
      cp = b0 & 0x1Fu;
      extra = 1;
    } else if ((b0 & 0xF0) == 0xE0) {
      cp = b0 & 0x0Fu;
      extra = 2;
    } else if ((b0 & 0xF8) == 0xF0) {
      cp = b0 & 0x07u;
      extra = 3;
    } else {
      *error = "UTF-8 tidak sah: byte awal " + HexByte(b0);
      return false;
    }
    if (i + extra >= n) {
      *error = "UTF-8 tidak sah: urutan terpotong";
      return false;
    }
    for (std::size_t k = 1; k <= extra; ++k) {
      const unsigned char bk = static_cast<unsigned char>(s[i + k]);
      if ((bk & 0xC0) != 0x80) {
        *error = "UTF-8 tidak sah: byte lanjutan salah";
        return false;
      }
      cp = (cp << 6) | (bk & 0x3Fu);
    }
    if ((extra == 1 && cp < 0x80u) || (extra == 2 && cp < 0x800u) ||
        (extra == 3 && cp < 0x10000u)) {
      *error = "UTF-8 tidak sah: bentuk overlong";
      return false;
    }
    if (cp >= 0xD800u && cp <= 0xDFFFu) {
      *error = "UTF-8 tidak sah: titik kode surrogate";
      return false;
    }
    if (cp > 0x10FFFFu) {
      *error = "UTF-8 tidak sah: di luar U+10FFFF";
      return false;
    }
    out->push_back(cp);
    i += extra + 1;
  }
  return true;
}

void AppendUtf8(std::uint32_t cp, std::string* out) {
  if (cp <= 0x7Fu) {
    out->push_back(static_cast<char>(cp));
  } else if (cp <= 0x7FFu) {
    out->push_back(static_cast<char>(0xC0u | (cp >> 6)));
    out->push_back(static_cast<char>(0x80u | (cp & 0x3Fu)));
  } else if (cp <= 0xFFFFu) {
    out->push_back(static_cast<char>(0xE0u | (cp >> 12)));
    out->push_back(static_cast<char>(0x80u | ((cp >> 6) & 0x3Fu)));
    out->push_back(static_cast<char>(0x80u | (cp & 0x3Fu)));
  } else {
    out->push_back(static_cast<char>(0xF0u | (cp >> 18)));
    out->push_back(static_cast<char>(0x80u | ((cp >> 12) & 0x3Fu)));
    out->push_back(static_cast<char>(0x80u | ((cp >> 6) & 0x3Fu)));
    out->push_back(static_cast<char>(0x80u | (cp & 0x3Fu)));
  }
}

// Code unit UTF-16, dipakai HANYA untuk pengurutan kunci (RFC 8785 §3.2.3).
std::vector<std::uint16_t> ToUtf16(const std::vector<std::uint32_t>& cps) {
  std::vector<std::uint16_t> u;
  u.reserve(cps.size());
  for (std::uint32_t cp : cps) {
    if (cp <= 0xFFFFu) {
      u.push_back(static_cast<std::uint16_t>(cp));
    } else {
      const std::uint32_t v = cp - 0x10000u;
      u.push_back(static_cast<std::uint16_t>(0xD800u + (v >> 10)));
      u.push_back(static_cast<std::uint16_t>(0xDC00u + (v & 0x3FFu)));
    }
  }
  return u;
}

// ---------------------------------------------------------------------------
// String (RFC 8785 §3.2.2.2)
// ---------------------------------------------------------------------------

void AppendEscapedString(const std::vector<std::uint32_t>& cps,
                         std::string* out) {
  out->push_back('"');
  for (std::uint32_t cp : cps) {
    switch (cp) {
      case 0x08u: out->append("\\b"); break;
      case 0x09u: out->append("\\t"); break;
      case 0x0Au: out->append("\\n"); break;
      case 0x0Cu: out->append("\\f"); break;
      case 0x0Du: out->append("\\r"); break;
      case 0x22u: out->append("\\\""); break;
      case 0x5Cu: out->append("\\\\"); break;
      default:
        if (cp < 0x20u) {
          char buf[8];
          std::snprintf(buf, sizeof(buf), "\\u%04x", cp);
          out->append(buf);
        } else {
          AppendUtf8(cp, out);
        }
    }
  }
  out->push_back('"');
}

// ---------------------------------------------------------------------------
// Angka (RFC 8785 §3.2.2.3, ECMAScript Number::toString)
// ---------------------------------------------------------------------------

// Menyerialkan double menurut ECMAScript. Inti: ambil digit terpendek yang
// bolak-balik tepat (shortest round-trip) lewat std::to_chars, lalu susun
// ulang menurut aturan penempatan titik desimal ECMAScript.
bool SerializeNumber(double v, std::string* out, std::string* error) {
  if (std::isnan(v) || std::isinf(v)) {
    *error = "angka NaN/Infinity tidak diizinkan di JSON";
    return false;
  }
  if (v == 0.0) {  // menangani +0 dan -0 sekaligus: keduanya -> "0"
    out->assign("0");
    return true;
  }

  // CATATAN PENTING (temuan nyata, bukan teori): std::to_chars mode "general"
  // pada libstdc++ GCC 12 menuliskan nilai EKSAK untuk bilangan bulat besar —
  // mis. 295147905179352825856 — bukan bentuk TERPENDEK yang bolak-balik tepat
  // (29514790517935283e4 = 295147905179352830000). RFC 8785 §3.2.2.3 mewajibkan
  // bentuk terpendek, jadi mode general TIDAK dapat dipakai. Mode "scientific"
  // memberi mantissa terpendek, sehingga itu yang dipakai.
  char buf[64];
  const std::to_chars_result r =
      std::to_chars(buf, buf + sizeof(buf), v, std::chars_format::scientific);
  if (r.ec != std::errc()) {
    *error = "gagal menyerialkan angka";
    return false;
  }
  std::string s(buf, r.ptr);
  {
    // Jaring pengaman: bentuk yang dihasilkan WAJIB bolak-balik ke double yang
    // sama. Bila tidak, lebih baik gagal-tertutup daripada menandatangani
    // bentuk yang tidak kanonik.
    char* end = nullptr;
    const double back = std::strtod(s.c_str(), &end);
    if (end == nullptr || *end != '\0' || back != v) {
      *error = "serialisasi angka tidak bolak-balik tepat";
      return false;
    }
  }

  // Pisahkan mantissa dan eksponen.
  std::string mant = s;
  int exp_from_e = 0;
  const std::size_t epos = s.find_first_of("eE");
  if (epos != std::string::npos) {
    mant = s.substr(0, epos);
    exp_from_e = std::atoi(s.c_str() + epos + 1);
  }

  bool neg = false;
  if (!mant.empty() && mant[0] == '-') {
    neg = true;
    mant.erase(0, 1);
  }

  const std::size_t dot = mant.find('.');
  std::string digits;
  int point_pos = 0;  // jumlah digit di kiri titik desimal
  if (dot == std::string::npos) {
    digits = mant;
    point_pos = static_cast<int>(mant.size());
  } else {
    digits = mant.substr(0, dot) + mant.substr(dot + 1);
    point_pos = static_cast<int>(dot);
  }

  // Buang nol di depan (jaga minimal satu digit).
  std::size_t lead = 0;
  while (lead + 1 < digits.size() && digits[lead] == '0') ++lead;
  digits.erase(0, lead);
  point_pos -= static_cast<int>(lead);

  // Buang nol di belakang.
  while (digits.size() > 1 && digits.back() == '0') digits.pop_back();

  const int n = static_cast<int>(digits.size());
  // Nilai = digits * 10^k
  const int k = exp_from_e + point_pos - n;

  std::string body;
  if (k >= 0 && n + k <= 21) {
    // Digit diikuti k nol.
    body = digits;
    body.append(static_cast<std::size_t>(k), '0');
  } else if (n + k > 0 && n + k <= 21) {
    // Titik desimal disisipkan setelah n+k digit.
    const int cut = n + k;
    body = digits.substr(0, static_cast<std::size_t>(cut)) + "." +
           digits.substr(static_cast<std::size_t>(cut));
  } else if (n + k > -6 && n + k <= 0) {
    // "0." + (-(n+k)) nol + digit.
    body = "0.";
    body.append(static_cast<std::size_t>(-(n + k)), '0');
    body += digits;
  } else {
    // Bentuk ilmiah.
    const int e = n + k - 1;
    if (n == 1) {
      body = digits;
    } else {
      body = digits.substr(0, 1) + "." + digits.substr(1);
    }
    body += "e";
    body += (e >= 0 ? "+" : "-");
    body += std::to_string(e >= 0 ? e : -e);
  }

  out->append(neg ? "-" + body : body);
  return true;
}

// ---------------------------------------------------------------------------
// Serializer rekursif
// ---------------------------------------------------------------------------

bool SerializeValue(const json::Value& v, std::string* out,
                    std::string* error) {
  switch (v.type()) {
    case json::Type::kNull:
      out->append("null");
      return true;
    case json::Type::kBool:
      out->append(v.bool_value() ? "true" : "false");
      return true;
    case json::Type::kNumber:
      return SerializeNumber(v.number_value(), out, error);
    case json::Type::kString: {
      std::vector<std::uint32_t> cps;
      if (!DecodeUtf8(v.string_value(), &cps, error)) return false;
      AppendEscapedString(cps, out);
      return true;
    }
    case json::Type::kArray: {
      out->push_back('[');
      bool first = true;
      for (const json::Value& e : v.elements()) {
        if (!first) out->push_back(',');
        first = false;
        if (!SerializeValue(e, out, error)) return false;
      }
      out->push_back(']');
      return true;
    }
    case json::Type::kObject: {
      // Urutkan kunci menurut code unit UTF-16 (RFC 8785 §3.2.3).
      struct Entry {
        std::vector<std::uint32_t> key_cps;    // titik kode, untuk escape
        std::vector<std::uint16_t> key_utf16;  // code unit, untuk pengurutan
        const json::Value* value;
      };
      std::vector<Entry> entries;
      entries.reserve(v.members().size());
      for (const auto& kv : v.members()) {
        std::vector<std::uint32_t> cps;
        if (!DecodeUtf8(kv.first, &cps, error)) return false;
        entries.push_back(Entry{cps, ToUtf16(cps), &kv.second});
      }
      std::stable_sort(entries.begin(), entries.end(),
                       [](const Entry& a, const Entry& b) {
                         return a.key_utf16 < b.key_utf16;
                       });
      out->push_back('{');
      bool first = true;
      for (const Entry& e : entries) {
        if (!first) out->push_back(',');
        first = false;
        // Nama kunci di-escape dengan aturan yang sama seperti string biasa.
        AppendEscapedString(e.key_cps, out);
        out->push_back(':');
        if (!SerializeValue(*e.value, out, error)) return false;
      }
      out->push_back('}');
      return true;
    }
  }
  *error = "tipe JSON tidak dikenal";
  return false;
}

}  // namespace

Result Canonicalize(const std::string& json_text) {
  Result result;
  const json::ParseResult parsed = json::Parse(json_text);
  if (!parsed.ok) {
    result.error = "JSON tidak sah: " + parsed.error;
    return result;
  }
  std::string out;
  std::string error;
  if (!SerializeValue(parsed.value, &out, &error)) {
    result.error = error;
    return result;
  }
  result.ok = true;
  result.canonical = std::move(out);
  return result;
}

Result CanonicalizeWithoutMember(const std::string& json_text,
                                 const std::string& member_to_remove) {
  Result result;
  const json::ParseResult parsed = json::Parse(json_text);
  if (!parsed.ok) {
    result.error = "JSON tidak sah: " + parsed.error;
    return result;
  }
  if (parsed.value.type() != json::Type::kObject) {
    result.error = "dokumen tingkat atas harus berupa objek JSON";
    return result;
  }
  // Bangun ulang objek tanpa anggota yang diminta.
  json::Value filtered;
  for (const auto& kv : parsed.value.members()) {
    if (kv.first == member_to_remove) continue;
    filtered.AddMember(kv.first, kv.second);
  }
  std::string out;
  std::string error;
  if (!SerializeValue(filtered, &out, &error)) {
    result.error = error;
    return result;
  }
  result.ok = true;
  result.canonical = std::move(out);
  return result;
}

}  // namespace jcs
}  // namespace nq
