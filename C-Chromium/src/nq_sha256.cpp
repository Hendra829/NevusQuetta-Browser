#include "nq_sha256.h"

#include <cstring>

namespace nq {
namespace {

// Putaran konstan FIPS 180-4 §4.2.2.
constexpr std::uint32_t kRoundConstants[64] = {
    0x428a2f98u, 0x71374491u, 0xb5c0fbcfu, 0xe9b5dba5u, 0x3956c25bu,
    0x59f111f1u, 0x923f82a4u, 0xab1c5ed5u, 0xd807aa98u, 0x12835b01u,
    0x243185beu, 0x550c7dc3u, 0x72be5d74u, 0x80deb1feu, 0x9bdc06a7u,
    0xc19bf174u, 0xe49b69c1u, 0xefbe4786u, 0x0fc19dc6u, 0x240ca1ccu,
    0x2de92c6fu, 0x4a7484aau, 0x5cb0a9dcu, 0x76f988dau, 0x983e5152u,
    0xa831c66du, 0xb00327c8u, 0xbf597fc7u, 0xc6e00bf3u, 0xd5a79147u,
    0x06ca6351u, 0x14292967u, 0x27b70a85u, 0x2e1b2138u, 0x4d2c6dfcu,
    0x53380d13u, 0x650a7354u, 0x766a0abbu, 0x81c2c92eu, 0x92722c85u,
    0xa2bfe8a1u, 0xa81a664bu, 0xc24b8b70u, 0xc76c51a3u, 0xd192e819u,
    0xd6990624u, 0xf40e3585u, 0x106aa070u, 0x19a4c116u, 0x1e376c08u,
    0x2748774cu, 0x34b0bcb5u, 0x391c0cb3u, 0x4ed8aa4au, 0x5b9cca4fu,
    0x682e6ff3u, 0x748f82eeu, 0x78a5636fu, 0x84c87814u, 0x8cc70208u,
    0x90befffau, 0xa4506cebu, 0xbef9a3f7u, 0xc67178f2u};

inline std::uint32_t Rotr(std::uint32_t x, unsigned n) {
  return (x >> n) | (x << (32u - n));
}
inline std::uint32_t Ch(std::uint32_t x, std::uint32_t y, std::uint32_t z) {
  return (x & y) ^ (~x & z);
}
inline std::uint32_t Maj(std::uint32_t x, std::uint32_t y, std::uint32_t z) {
  return (x & y) ^ (x & z) ^ (y & z);
}
inline std::uint32_t BigSigma0(std::uint32_t x) {
  return Rotr(x, 2) ^ Rotr(x, 13) ^ Rotr(x, 22);
}
inline std::uint32_t BigSigma1(std::uint32_t x) {
  return Rotr(x, 6) ^ Rotr(x, 11) ^ Rotr(x, 25);
}
inline std::uint32_t SmallSigma0(std::uint32_t x) {
  return Rotr(x, 7) ^ Rotr(x, 18) ^ (x >> 3);
}
inline std::uint32_t SmallSigma1(std::uint32_t x) {
  return Rotr(x, 17) ^ Rotr(x, 19) ^ (x >> 10);
}

int HexVal(char c) {
  if (c >= '0' && c <= '9') return c - '0';
  if (c >= 'a' && c <= 'f') return c - 'a' + 10;
  if (c >= 'A' && c <= 'F') return c - 'A' + 10;
  return -1;
}

}  // namespace

Sha256::Sha256()
    : state_{0x6a09e667u, 0xbb67ae85u, 0x3c6ef372u, 0xa54ff53au, 0x510e527fu,
             0x9b05688cu, 0x1f83d9abu, 0x5be0cd19u},
      buffer_{},
      buffer_len_(0),
      total_bytes_(0),
      finalized_(false) {}

void Sha256::Transform(const std::uint8_t* block) {
  std::uint32_t w[64];
  for (int i = 0; i < 16; ++i) {
    w[i] = (static_cast<std::uint32_t>(block[i * 4]) << 24) |
           (static_cast<std::uint32_t>(block[i * 4 + 1]) << 16) |
           (static_cast<std::uint32_t>(block[i * 4 + 2]) << 8) |
           (static_cast<std::uint32_t>(block[i * 4 + 3]));
  }
  for (int i = 16; i < 64; ++i) {
    w[i] = SmallSigma1(w[i - 2]) + w[i - 7] + SmallSigma0(w[i - 15]) +
           w[i - 16];
  }

  std::uint32_t a = state_[0], b = state_[1], c = state_[2], d = state_[3];
  std::uint32_t e = state_[4], f = state_[5], g = state_[6], h = state_[7];

  for (int i = 0; i < 64; ++i) {
    const std::uint32_t t1 =
        h + BigSigma1(e) + Ch(e, f, g) + kRoundConstants[i] + w[i];
    const std::uint32_t t2 = BigSigma0(a) + Maj(a, b, c);
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

void Sha256::Update(const void* data, std::size_t len) {
  if (data == nullptr || len == 0) return;
  const auto* bytes = static_cast<const std::uint8_t*>(data);
  total_bytes_ += len;

  std::size_t offset = 0;
  if (buffer_len_ > 0) {
    const std::size_t want = 64 - buffer_len_;
    const std::size_t take = (len < want) ? len : want;
    std::memcpy(buffer_ + buffer_len_, bytes, take);
    buffer_len_ += take;
    offset += take;
    if (buffer_len_ == 64) {
      Transform(buffer_);
      buffer_len_ = 0;
    }
  }
  while (len - offset >= 64) {
    Transform(bytes + offset);
    offset += 64;
  }
  if (offset < len) {
    std::memcpy(buffer_, bytes + offset, len - offset);
    buffer_len_ = len - offset;
  }
}

void Sha256::Update(const std::string& text) {
  Update(text.data(), text.size());
}

Sha256::Digest Sha256::Final() {
  // Padding deterministik: 0x80, lalu nol, lalu panjang bit 64-bit big-endian.
  const std::uint64_t bit_len = total_bytes_ * 8u;
  const std::uint8_t pad = 0x80;
  Update(&pad, 1);
  const std::uint8_t zero = 0x00;
  while (buffer_len_ != 56) {
    Update(&zero, 1);
  }
  std::uint8_t len_bytes[8];
  for (int i = 0; i < 8; ++i) {
    len_bytes[i] = static_cast<std::uint8_t>(bit_len >> (56 - i * 8));
  }
  Update(len_bytes, 8);
  finalized_ = true;

  Digest out{};
  for (int i = 0; i < 8; ++i) {
    out[i * 4] = static_cast<std::uint8_t>(state_[i] >> 24);
    out[i * 4 + 1] = static_cast<std::uint8_t>(state_[i] >> 16);
    out[i * 4 + 2] = static_cast<std::uint8_t>(state_[i] >> 8);
    out[i * 4 + 3] = static_cast<std::uint8_t>(state_[i]);
  }
  return out;
}

Sha256::Digest Sha256::Hash(const std::string& text) {
  Sha256 h;
  h.Update(text);
  return h.Final();
}

std::string Sha256::ToHex(const Digest& digest) {
  static const char* kHex = "0123456789abcdef";
  std::string out;
  out.reserve(kDigestBytes * 2);
  for (std::uint8_t b : digest) {
    out.push_back(kHex[b >> 4]);
    out.push_back(kHex[b & 0x0Fu]);
  }
  return out;
}

bool Sha256::FromHex(const std::string& hex, Digest* out) {
  if (out == nullptr) return false;
  if (hex.size() != kDigestBytes * 2) return false;
  Digest tmp{};
  for (std::size_t i = 0; i < kDigestBytes; ++i) {
    const int hi = HexVal(hex[i * 2]);
    const int lo = HexVal(hex[i * 2 + 1]);
    if (hi < 0 || lo < 0) return false;
    tmp[i] = static_cast<std::uint8_t>((hi << 4) | lo);
  }
  *out = tmp;
  return true;
}

}  // namespace nq
