#include "nq_sha512.h"

#include <cstring>

#include "nq_sha512_constants.h"

namespace nq {
namespace {

inline std::uint64_t Ror(std::uint64_t x, unsigned n) {
  return (x >> n) | (x << (64 - n));
}

inline std::uint64_t Ch(std::uint64_t x, std::uint64_t y, std::uint64_t z) {
  return (x & y) ^ (~x & z);
}

inline std::uint64_t Maj(std::uint64_t x, std::uint64_t y, std::uint64_t z) {
  return (x & y) ^ (x & z) ^ (y & z);
}

inline std::uint64_t BigSigma0(std::uint64_t x) {
  return Ror(x, 28) ^ Ror(x, 34) ^ Ror(x, 39);
}

inline std::uint64_t BigSigma1(std::uint64_t x) {
  return Ror(x, 14) ^ Ror(x, 18) ^ Ror(x, 41);
}

inline std::uint64_t SmallSigma0(std::uint64_t x) {
  return Ror(x, 1) ^ Ror(x, 8) ^ (x >> 7);
}

inline std::uint64_t SmallSigma1(std::uint64_t x) {
  return Ror(x, 19) ^ Ror(x, 61) ^ (x >> 6);
}

inline std::uint64_t LoadBig(const std::uint8_t* p) {
  return (static_cast<std::uint64_t>(p[0]) << 56) |
         (static_cast<std::uint64_t>(p[1]) << 48) |
         (static_cast<std::uint64_t>(p[2]) << 40) |
         (static_cast<std::uint64_t>(p[3]) << 32) |
         (static_cast<std::uint64_t>(p[4]) << 24) |
         (static_cast<std::uint64_t>(p[5]) << 16) |
         (static_cast<std::uint64_t>(p[6]) << 8) |
         (static_cast<std::uint64_t>(p[7]));
}

inline void StoreBig(std::uint64_t v, std::uint8_t* p) {
  p[0] = static_cast<std::uint8_t>(v >> 56);
  p[1] = static_cast<std::uint8_t>(v >> 48);
  p[2] = static_cast<std::uint8_t>(v >> 40);
  p[3] = static_cast<std::uint8_t>(v >> 32);
  p[4] = static_cast<std::uint8_t>(v >> 24);
  p[5] = static_cast<std::uint8_t>(v >> 16);
  p[6] = static_cast<std::uint8_t>(v >> 8);
  p[7] = static_cast<std::uint8_t>(v);
}

}  // namespace

Sha512::Sha512()
    : state_{0x6A09E667F3BCC908ULL, 0xBB67AE8584CAA73BULL,
             0x3C6EF372FE94F82BULL, 0xA54FF53A5F1D36F1ULL,
             0x510E527FADE682D1ULL, 0x9B05688C2B3E6C1FULL,
             0x1F83D9ABFB41BD6BULL, 0x5BE0CD19137E2179ULL},
      buffer_len_(0),
      total_bits_high_(0),
      total_bits_low_(0),
      finalized_(false) {
  std::memset(buffer_, 0, sizeof(buffer_));
}

void Sha512::Transform(const std::uint8_t* block) {
  std::uint64_t w[80];
  for (int t = 0; t < 16; ++t) {
    w[t] = LoadBig(block + t * 8);
  }
  for (int t = 16; t < 80; ++t) {
    w[t] = SmallSigma1(w[t - 2]) + w[t - 7] + SmallSigma0(w[t - 15]) +
           w[t - 16];
  }

  std::uint64_t a = state_[0], b = state_[1], c = state_[2], d = state_[3];
  std::uint64_t e = state_[4], f = state_[5], g = state_[6], h = state_[7];

  for (int t = 0; t < 80; ++t) {
    const std::uint64_t t1 =
        h + BigSigma1(e) + Ch(e, f, g) + sha512_detail::kK[t] + w[t];
    const std::uint64_t t2 = BigSigma0(a) + Maj(a, b, c);
    h = g;
    g = f;
    f = e;
    e = d + t1;
    d = c;
    c = b;
    b = a;
    a = t1 + t2;
  }

  state_[0] += a;
  state_[1] += b;
  state_[2] += c;
  state_[3] += d;
  state_[4] += e;
  state_[5] += f;
  state_[6] += g;
  state_[7] += h;
}

void Sha512::Update(const void* data, std::size_t len) {
  if (finalized_ || len == 0) return;
  const std::uint8_t* p = static_cast<const std::uint8_t*>(data);

  // Penghitung bit 128 bit: len (size_t, <= 2^64-1) dikali 8 tidak meluap di
  // 64 bit rendah hanya bila len < 2^61. Untuk keamanan, hitung dalam 128 bit.
  const std::uint64_t add_low = static_cast<std::uint64_t>(len) << 3;
  const std::uint64_t add_high = static_cast<std::uint64_t>(len) >> 61;
  total_bits_low_ += add_low;
  if (total_bits_low_ < add_low) ++total_bits_high_;
  total_bits_high_ += add_high;

  while (len > 0) {
    const std::size_t space = 128 - buffer_len_;
    const std::size_t take = len < space ? len : space;
    std::memcpy(buffer_ + buffer_len_, p, take);
    buffer_len_ += take;
    p += take;
    len -= take;
    if (buffer_len_ == 128) {
      Transform(buffer_);
      buffer_len_ = 0;
    }
  }
}

void Sha512::Update(const std::string& text) {
  Update(text.data(), text.size());
}

Sha512::Digest Sha512::Final() {
  if (finalized_) return cached_;  // idempoten, BUKAN digest nol
  finalized_ = true;

  // Padding: 0x80, lalu nol, lalu panjang 128 bit big-endian.
  //
  // CATATAN: padding dibangun di buffer lokal dan TIDAK lewat Update(), karena
  // finalized_ sudah true sehingga Update() akan mengabaikannya. Kesalahan itu
  // pernah ada di versi pertama berkas ini dan tertangkap oleh uji vektor
  // SHA-512; jangan "menyederhanakannya" kembali.
  const std::uint64_t bits_high = total_bits_high_;
  const std::uint64_t bits_low = total_bits_low_;

  std::uint8_t tail[256];
  const std::size_t target = (buffer_len_ < 112) ? 112u : 240u;
  std::size_t tail_len = 0;
  std::memcpy(tail, buffer_, buffer_len_);
  tail_len = buffer_len_;
  tail[tail_len++] = 0x80;
  while (tail_len < target) {
    tail[tail_len++] = 0x00;
  }
  StoreBig(bits_high, tail + tail_len);
  tail_len += 8;
  StoreBig(bits_low, tail + tail_len);
  tail_len += 8;  // tail_len kini 128 atau 256 (kelipatan blok)

  for (std::size_t off = 0; off < tail_len; off += 128) {
    Transform(tail + off);
  }
  buffer_len_ = 0;

  for (int i = 0; i < 8; ++i) {
    StoreBig(state_[i], cached_.data() + i * 8);
  }
  return cached_;
}

Sha512::Digest Sha512::Hash(const void* data, std::size_t len) {
  Sha512 h;
  h.Update(data, len);
  return h.Final();
}

Sha512::Digest Sha512::Hash(const std::string& text) {
  return Hash(text.data(), text.size());
}

std::string Sha512::ToHex(const Digest& digest) {
  static const char* kHex = "0123456789abcdef";
  std::string out;
  out.reserve(kDigestBytes * 2);
  for (std::uint8_t byte : digest) {
    out.push_back(kHex[byte >> 4]);
    out.push_back(kHex[byte & 0x0F]);
  }
  return out;
}

bool Sha512::FromHex(const std::string& hex, Digest* out) {
  if (out == nullptr || hex.size() != kDigestBytes * 2) return false;
  Digest parsed{};
  for (std::size_t i = 0; i < kDigestBytes; ++i) {
    unsigned value = 0;
    for (int nibble = 0; nibble < 2; ++nibble) {
      const char c = hex[i * 2 + nibble];
      value <<= 4;
      if (c >= '0' && c <= '9') {
        value |= static_cast<unsigned>(c - '0');
      } else if (c >= 'a' && c <= 'f') {
        value |= static_cast<unsigned>(c - 'a' + 10);
      } else if (c >= 'A' && c <= 'F') {
        value |= static_cast<unsigned>(c - 'A' + 10);
      } else {
        return false;
      }
    }
    parsed[i] = static_cast<std::uint8_t>(value);
  }
  *out = parsed;
  return true;
}

}  // namespace nq
