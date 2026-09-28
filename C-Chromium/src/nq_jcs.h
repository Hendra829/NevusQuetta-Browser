#pragma once

#include <string>

// Kanonikalisasi JSON (RFC 8785, JSON Canonicalization Scheme).
//
// Alasan keberadaan: verifier tanda tangan ruleset menerima "teks berkas apa
// adanya". Akibatnya ruleset yang setara secara semantik tetapi berbeda format
// (urutan kunci, spasi, escape) menghasilkan tanda tangan berbeda, dan
// penyuntingan kosmetik membatalkan tanda tangan yang sah. JCS menutup itu:
// satu dokumen -> satu bentuk byte kanonik.
//
// Yang dilakukan:
//   * ruang kosong antar token dibuang (RFC 8785 §3.2.1);
//   * kunci objek diurutkan menurut code unit UTF-16 (§3.2.3);
//   * string di-escape menurut ECMAScript JSON.stringify (§3.2.2.2);
//   * angka diserialkan menurut ECMAScript Number::toString (§3.2.2.3), yaitu
//     representasi terpendek yang bolak-balik (round-trip) tepat;
//   * urutan elemen array TIDAK diubah.
//
// Gagal-tertutup: JSON tidak sah, angka NaN/Infinity, atau UTF-8 tidak sah
// menghasilkan ok=false dan canonical kosong. Pemanggil WAJIB memperlakukan
// kegagalan sebagai penolakan, bukan sebagai "tidak ada kanonikalisasi".
namespace nq {
namespace jcs {

struct Result {
  bool ok = false;
  std::string canonical;
  std::string error;
};

// Kanonikalisasi teks JSON menurut RFC 8785.
Result Canonicalize(const std::string& json_text);

// Sama seperti Canonicalize, tetapi satu anggota objek TINGKAT ATAS dibuang
// lebih dulu. Dipakai untuk mengeluarkan blok "signature" dari payload tanda
// tangan: tanda tangan dihitung atas dokumen TANPA blok tanda tangan itu
// sendiri (kalau tidak, tanda tangan tidak dapat dihitung).
//
// Bila anggota tersebut tidak ada, hasilnya sama dengan Canonicalize.
Result CanonicalizeWithoutMember(const std::string& json_text,
                                 const std::string& member_to_remove);

}  // namespace jcs
}  // namespace nq
