#!/usr/bin/env python3
"""Membangkitkan konstanta SHA-512 secara EKSAK (FIPS 180-4).

K[t] = 64 bit pertama dari bagian pecahan akar kubik bilangan prima ke-t.
    K[t] = floor(frac(p_t^(1/3)) * 2^64)
         = floor((p_t * 2^192)^(1/3)) - floor(p_t^(1/3)) * 2^64

Dihitung dengan aritmetika bilangan bulat tak terbatas milik Python, sehingga
tidak ada konstanta 64-bit yang perlu diketik dari ingatan. Skrip ini menulis
src/nq_sha512_constants.h. Kebenarannya diverifikasi terpisah oleh uji
end-to-end: SHA-512("") harus sama dengan sha512sum.
"""
import sys


def iroot3(n: int) -> int:
    """Akar kubik bilangan bulat (floor), eksak via Newton."""
    if n < 0:
        raise ValueError("n negatif")
    if n == 0:
        return 0
    x = 1 << ((n.bit_length() + 2) // 3)   # tebakan awal >= akar sebenarnya
    while True:
        y = (2 * x + n // (x * x)) // 3
        if y >= x:
            break
        x = y
    # x adalah akar kubik floor (Newton monoton turun ke bawah).
    while x * x * x > n:
        x -= 1
    while (x + 1) ** 3 <= n:
        x += 1
    return x


def primes(n_primes: int):
    out, n = [], 2
    while len(out) < n_primes:
        if all(n % d for d in range(2, int(n ** 0.5) + 1)):
            out.append(n)
        n += 1
    return out


def main() -> int:
    ps = primes(80)
    assert ps[0] == 2 and ps[79] == 409, ps[79]
    k = []
    for p in ps:
        # floor(cbrt(p) * 2^64) = iroot3(p << 192);  floor(cbrt(p)) = iroot3(p)
        k.append(iroot3(p << 192) - (iroot3(p) << 64))

    with open("src/nq_sha512_constants.h", "w", encoding="utf-8") as f:
        f.write("// DIBANGKITKAN OTOMATIS oleh tools/gen_sha512_constants.py\n")
        f.write("// JANGAN diedit manual. Regenerasi: python3 tools/gen_sha512_constants.py\n")
        f.write("//\n")
        f.write("// K[t] = 64 bit pertama bagian pecahan akar kubik bilangan prima ke-t\n")
        f.write("// (FIPS 180-4). Dihitung eksak dengan aritmetika bilangan besar.\n")
        f.write("#pragma once\n\n#include <cstdint>\n\nnamespace nq {\n")
        f.write("namespace sha512_detail {\n\n")
        f.write("static const std::uint64_t kK[80] = {\n")
        for row in range(0, 80, 4):
            chunk = ", ".join("0x%016xULL" % k[i] for i in range(row, row + 4))
            f.write("    " + chunk + ",\n")
        f.write("};\n\n}  // namespace sha512_detail\n}  // namespace nq\n")

    # Bukti cepat: konstanta pertama harus sama dengan nilai SHA-512 yang telah
    # lama dipublikasikan (diperiksa ulang di sini sebagai jaring pengaman).
    if k[0] != 0x428A2F98D728AE22:
        print("PERINGATAN: K[0] tidak sesuai nilai rujukan SHA-512", hex(k[0]))
        return 1
    print("kK[0] =", hex(k[0]))
    print("kK[79] =", hex(k[79]))
    print("Konstanta SHA-512 ditulis ke src/nq_sha512_constants.h")
    return 0


if __name__ == "__main__":
    sys.exit(main())
