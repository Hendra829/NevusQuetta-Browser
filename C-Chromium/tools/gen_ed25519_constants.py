#!/usr/bin/env python3
"""Membangkitkan konstanta Ed25519 secara EKSAK (RFC 8032).

Tidak ada satu pun konstanta 64-bit yang diketik dari ingatan. Semuanya
diturunkan dari p = 2^255 - 19 dan d = -121665/121666, lalu DIPERIKSA di sini
sebelum ditulis. Bila ada pemeriksaan gagal, skrip keluar dengan kode bukan-nol
dan TIDAK menulis berkas.
"""
import sys

P = 2 ** 255 - 19
L = 2 ** 252 + 27742317777372353535851937790883648493
MASK51 = (1 << 51) - 1


def limbs(x):
    """Limb 51-bit (untuk pemeriksaan struktural p = 2^255-19)."""
    return [(x >> (51 * i)) & MASK51 for i in range(5)]


def w32(x):
    """Limb 32-bit little-endian (representasi field yang dipakai nq_ed25519.cpp)."""
    return [(x >> (32 * i)) & 0xFFFFFFFF for i in range(8)]


def c32(name, x, comment):
    vals = ", ".join("0x%08xu" % v for v in w32(x))
    return (f"// {comment}\n"
            f"static const std::uint32_t {name}[8] = {{ {vals} }};")


def enc_y_le(y, sign=0):
    b = bytearray(y.to_bytes(32, "little"))
    b[31] |= (sign & 1) << 7
    return bytes(b)


def main() -> int:
    # --- Konstanta kurva ---
    d = (-121665 * pow(121666, P - 2, P)) % P
    d2 = (2 * d) % P
    sqrtm1 = pow(2, (P - 1) // 4, P)
    # Pilih akar "kecil" agar cocok dengan konvensi ref10.
    if sqrtm1 > P - sqrtm1:
        sqrtm1 = P - sqrtm1

    # --- Titik basis B: y = 4/5, x = akar genap dari (y^2-1)/(d y^2+1) ---
    y = (4 * pow(5, P - 2, P)) % P
    u = (y * y - 1) % P
    v = (d * y * y + 1) % P
    x = pow(u * pow(v, P - 2, P), (P + 3) // 8, P)
    if (v * x * x - u) % P != 0:
        x = (x * sqrtm1) % P
    if x % 2 == 1:
        x = P - x
    base_enc = enc_y_le(y, 0)

    # --- Eksponen ---
    e_inv = P - 2            # untuk inversi lewat pemangkatan
    e_sqrt = (P - 5) // 8    # untuk akar kuadrat

    # --- PEMERIKSAAN (gagal-keras) ---
    checks = [
        ("p bitlen 255", P.bit_length() == 255),
        ("d pada kurva", (d * 121666 + 121665) % P == 0),
        ("d2 == 2d", d2 == (2 * d) % P),
        ("sqrtm1^2 == -1", (sqrtm1 * sqrtm1) % P == P - 1),
        ("base enc == 5866..66", base_enc.hex() == "58" + "66" * 31),
        ("p limb0", limbs(P)[0] == (1 << 51) - 19),
        ("p limb1..4", limbs(P)[1:] == [(1 << 51) - 1] * 4),
        ("e_inv limb", e_inv == 0x7FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEB),
        ("e_sqrt limb", e_sqrt == 0x0FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFD),
        ("8*e_sqrt+5 == p", 8 * e_sqrt + 5 == P),
        # Uji Fermat: a^(p-1) == 1, dan a^(p-2) benar-benar inversi a.
        ("Fermat 2^(p-1) == 1", pow(2, P - 1, P) == 1),
        ("e_inv inversi basis 3", pow(3, e_inv, P) * 3 % P == 1),
        ("e_sqrt: 8*e_sqrt+5 == p", 8 * e_sqrt + 5 == P),
        # L = 2^252 + c; bit 252 berada di limb 51-bit ke-4 pada posisi 48.
        ("L limb4 bit48", limbs(L)[4] == (1 << 48)),
        ("L < p", L < P),
    ]
    failed = [name for name, ok in checks if not ok]
    if failed:
        for name in failed:
            print("GAGAL pemeriksaan:", name, file=sys.stderr)
        return 1

    def arr(name, values, comment):
        """Pembantu lama (limb 51-bit) — tidak lagi dipakai untuk header."""
        out = [f"// {comment}", f"static const std::uint64_t {name}[5] = {{"]
        out.append("    " + ", ".join("0x%016xULL" % t for t in values) + ",")
        out.append("};")
        return "\n".join(out)

    del arr  # representasi field memakai limb 32-bit (lihat c32)

    parts = []
    parts.append("// DIBANGKITKAN OTOMATIS oleh tools/gen_ed25519_constants.py")
    parts.append("// JANGAN diedit manual. Regenerasi: python3 tools/gen_ed25519_constants.py")
    parts.append("//")
    parts.append("// Semua nilai diturunkan dari p = 2^255-19 dan d = -121665/121666,")
    parts.append("// lalu diperiksa ulang oleh skrip pembangkit sebelum ditulis.")
    parts.append("#pragma once")
    parts.append("")
    parts.append("#include <cstdint>")
    parts.append("")
    parts.append("namespace nq {")
    parts.append("namespace ed25519_detail {")
    parts.append("")
    parts.append("static const std::uint64_t kMask51 = 0x0007ffffffffffffULL;")
    parts.append("")
    parts.append(c32("kP", P, "p = 2^255 - 19"))
    parts.append(c32("kD", d, "d = -121665/121666 mod p"))
    parts.append("")
    parts.append("// orde subgroup L; L memuat 2^252 sehingga limb 7 = 0x10000000")
    parts.append("// (bit 252 = bit 28 dari limb ke-7). Dipakai untuk reduksi scalar s.")
    parts.append("static const std::uint32_t kL[8] = {")
    parts.append("    " + ", ".join("0x%08xu" % v for v in w32(L)) + ",")
    parts.append("};")
    parts.append("")

    for name, exp, comment in (("kExponentInvert", e_inv, "p-2: eksponen inversi"),
                               ("kExponentSqrt", e_sqrt, "(p-5)/8: eksponen akar kuadrat")):
        b = exp.to_bytes(32, "little")
        parts.append(f"// {comment} ({exp.bit_length()} bit), little-endian 32 byte")
        parts.append(f"static const unsigned char {name}[32] = {{")
        for row in range(0, 32, 8):
            parts.append("    " + ", ".join("0x%02x" % c for c in b[row:row + 8]) + ",")
        parts.append("};")
        parts.append("")

    parts.append("// Titik basis B yang terkompresi (y = 4/5, tanda x = 0).")
    parts.append("static const unsigned char kBasePointCompressed[32] = {")
    for row in range(0, 32, 8):
        parts.append("    " + ", ".join("0x%02x" % c for c in base_enc[row:row + 8]) + ",")
    parts.append("};")
    parts.append("")
    parts.append("}  // namespace ed25519_detail")
    parts.append("}  // namespace nq")

    with open("src/nq_ed25519_constants.h", "w", encoding="utf-8") as f:
        f.write("\n".join(parts) + "\n")

    print("Semua %d pemeriksaan konstanta LULUS." % len(checks))
    print("  d limb0   =", hex(limbs(d)[0]))
    print("  base enc  =", base_enc.hex())
    print("  ditulis   : src/nq_ed25519_constants.h")
    return 0


if __name__ == "__main__":
    sys.exit(main())
