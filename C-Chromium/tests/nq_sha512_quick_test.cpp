// Uji SHA-512 (FIPS 180-4) terhadap vektor rujukan yang dihasilkan secara
// independen oleh `python3 -c "hashlib.sha512(...)"` pada lingkungan ini.
//
// Panjang yang diuji sengaja mencakup batas padding: 111/112/113 (batas 112
// byte) dan 127/128/129 (batas blok 128 byte). Kesalahan padding paling sering
// muncul tepat di sekitar nilai-nilai ini.
#include <cstdio>
#include <string>

#include "nq_sha512.h"

namespace {

int g_fail = 0;
int g_total = 0;

void Expect(const std::string& what, const std::string& got,
            const std::string& want) {
  ++g_total;
  if (got == want) {
    std::printf("  OK    %s\n", what.c_str());
  } else {
    std::printf("  GAGAL %s\n        dapat: %s\n        harus: %s\n",
                what.c_str(), got.c_str(), want.c_str());
    ++g_fail;
  }
}

struct Vector {
  std::size_t length;
  const char* hex;
};

// Dihasilkan oleh hashlib.sha512 pada lingkungan audit (bukan diketik dari
// ingatan); silakan regenerasi dengan tools/verify_sha512_vectors.sh.
const Vector kVectors[] = {
    {0,
     "cf83e1357eefb8bdf1542850d66d8007d620e4050b5715dc83f4a921d36ce9ce"
     "47d0d13c5d85f2b0ff8318d2877eec2f63b931bd47417a81a538327af927da3e"},
    {1,
     "1f40fc92da241694750979ee6cf582f2d5d7d28e18335de05abc54d0560e0f53"
     "02860c652bf08d560252aa5e74210546f369fbbbce8c12cfc7957b2652fe9a75"},
    {55,
     "b0220c772cbf6c1822e2cb38a437d0e1d58772417a4bbb21c961364f8b6143e0"
     "5aa6316dca8d1d7b19e16448419076395f6086cb55101fbd6d5497b148e1745f"},
    {56,
     "962b64aae357d2a4fee3ded8b539bdc9d325081822b0bfc55583133aab44f18b"
     "afe11d72a7ae16c79ce2ba620ae2242d5144809161945f1367f41b3972e26e04"},
    {111,
     "fa9121c7b32b9e01733d034cfc78cbf67f926c7ed83e82200ef8681819692176"
     "0b4beff48404df811b953828274461673c68d04e297b0eb7b2b4d60fc6b566a2"},
    {112,
     "c01d080efd492776a1c43bd23dd99d0a2e626d481e16782e75d54c2503b5dc32"
     "bd05f0f1ba33e568b88fd2d970929b719ecbb152f58f130a407c8830604b70ca"},
    {113,
     "55ddd8ac210a6e18ba1ee055af84c966e0dbff091c43580ae1be703bdb85da31"
     "acf6948cf5bd90c55a20e5450f22fb89bd8d0085e39f85a86cc46abbca75e24d"},
    {127,
     "828613968b501dc00a97e08c73b118aa8876c26b8aac93df128502ab360f91ba"
     "b50a51e088769a5c1eff4782ace147dce3642554199876374291f5d921629502"},
    {128,
     "b73d1929aa615934e61a871596b3f3b33359f42b8175602e89f7e06e5f658a24"
     "3667807ed300314b95cacdd579f3e33abdfbe351909519a846d465c59582f321"},
    {129,
     "4f681e0bd53cda4b5a2041cc8a06f2eabde44fb16c951fbd5b87702f07aeab61"
     "1565b19c47fde30587177ebb852e3971bbd8d3fd30da18d71037dfbd98420429"},
    {255,
     "d8b5a659e365f704ab114ae7079a8da24fb9997b3052a4a63b37d654652bad6f"
     "bdd2b52d737e20a9d5ac3c5831d6afdd32ff737a3dd95269d2793bc2aa850aab"},
    {256,
     "6a9169eb662f136d87374070e8828b3e615a7eca32a89446e9225b02832709be"
     "095e635c824a2bb70213ba2ea0ababac0809827843992c851903b7ac0c136699"},
    {1000,
     "67ba5535a46e3f86dbfbed8cbbaf0125c76ed549ff8b0b9e03e0c88cf90fa634"
     "fa7b12b47d77b694de488ace8d9a65967dc96df599727d3292a8d9d447709c97"},
};

}  // namespace

int main() {
  std::printf("== Uji SHA-512 (FIPS 180-4) ==\n");

  Expect("SHA512(\"\")",
         nq::Sha512::ToHex(nq::Sha512::Hash(std::string())), kVectors[0].hex);
  Expect("SHA512(\"abc\")",
         nq::Sha512::ToHex(nq::Sha512::Hash(std::string("abc"))),
         "ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a"
         "2192992a274fc1a836ba3c23a3feebbd454d4423643ce80e2a9ac94fa54ca49f");

  for (const Vector& v : kVectors) {
    char label[64];
    std::snprintf(label, sizeof(label), "SHA512('a' x %zu)", v.length);
    Expect(label,
           nq::Sha512::ToHex(nq::Sha512::Hash(std::string(v.length, 'a'))),
           v.hex);
  }

  // Update bertahap harus memberi hasil sama dengan satu kali Update.
  {
    nq::Sha512 streamed;
    const std::string text(1000, 'a');
    for (std::size_t i = 0; i < text.size(); i += 7) {
      const std::size_t len =
          (text.size() - i < 7) ? (text.size() - i) : 7u;
      streamed.Update(text.data() + i, len);
    }
    Expect("SHA512 bertahap 7 byte x 143",
           nq::Sha512::ToHex(streamed.Final()), kVectors[12].hex);
  }

  // Final() ganda tidak boleh mengubah hasil (idempoten, bukan hash lanjutan).
  {
    nq::Sha512 h;
    h.Update(std::string("abc"));
    const std::string first = nq::Sha512::ToHex(h.Final());
    const std::string second = nq::Sha512::ToHex(h.Final());
    Expect("Final() idempoten", second, first);
  }

  // Update setelah Final() harus diabaikan, bukan memperpanjang hash.
  {
    nq::Sha512 h;
    h.Update(std::string("abc"));
    const std::string before = nq::Sha512::ToHex(h.Final());
    h.Update(std::string("tambahan"));
    Expect("Update setelah Final() diabaikan",
           nq::Sha512::ToHex(h.Final()), before);
  }

  std::printf("  ---\n  %d pemeriksaan, %d gagal\n", g_total, g_fail);
  return g_fail == 0 ? 0 : 1;
}
