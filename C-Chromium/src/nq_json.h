#pragma once

#include <cstddef>
#include <string>
#include <utility>
#include <vector>

// Parser JSON minimal tetapi KETAT (RFC 8259), tanpa dependensi eksternal.
//
// Alasan keberadaan: berkas ruleset adalah berkas konfigurasi keamanan. Parser
// yang "permisif" membuat berkas yang rusak terbaca sebagai kebijakan yang
// longgar, dan itu persis mode kegagalan yang harus dicegah. Karena itu:
//   * isi setelah nilai JSON pertama -> galat
//   * kunci objek DUPLIKAT            -> galat (ambiguitas = bahaya)
//   * escape \u tak lengkap / surrogate menggantung -> galat
//   * angka tidak sesuai tata bahasa (mis. 01, +1, 1., NaN) -> galat
//   * kedalaman / jumlah simpul dibatasi agar berkas jahat tidak menghabiskan memori
namespace nq {
namespace json {

enum class Type { kNull, kBool, kNumber, kString, kObject, kArray };

class Value {
 public:
  Value() = default;
  explicit Value(Type type) : type_(type) {}
  explicit Value(bool v) : type_(Type::kBool), bool_value_(v) {}

  Type type() const { return type_; }
  bool IsNull() const { return type_ == Type::kNull; }
  bool IsBool() const { return type_ == Type::kBool; }
  bool IsNumber() const { return type_ == Type::kNumber; }
  bool IsString() const { return type_ == Type::kString; }
  bool IsObject() const { return type_ == Type::kObject; }
  bool IsArray() const { return type_ == Type::kArray; }

  bool bool_value() const { return bool_value_; }
  double number_value() const { return number_value_; }
  const std::string& string_value() const { return string_value_; }
  const std::vector<std::pair<std::string, Value>>& members() const {
    return members_;
  }
  const std::vector<Value>& elements() const { return elements_; }

  // nullptr bila tidak ada. Kunci duplikat sudah ditolak parser, sehingga
  // pencarian "pertama" selalu sama dengan "satu-satunya".
  const Value* Find(const std::string& key) const;

  void set_bool(bool v);
  void set_number(double v);
  void set_string(std::string v);
  void AddMember(std::string key, Value value);
  void AddElement(Value value);

 private:
  Type type_ = Type::kNull;
  bool bool_value_ = false;
  double number_value_ = 0.0;
  std::string string_value_;
  std::vector<std::pair<std::string, Value>> members_;
  std::vector<Value> elements_;
};

struct Limit {
  std::size_t max_depth = 32;
  std::size_t max_nodes = 1u << 20;  // ~1 juta simpul
};

struct ParseResult {
  bool ok = false;
  Value value;
  std::string error;        // pesan bahasa Indonesia, siap ditampilkan
  std::size_t error_offset = 0;  // posisi byte pada teks masukan
};

// Mengurai tepat satu nilai JSON. Ruang kosong di depan/belakang diizinkan,
// tetapi tidak ada byte lain setelah nilai pertama.
ParseResult Parse(const std::string& text, const Limit& limit = Limit());

}  // namespace json
}  // namespace nq
