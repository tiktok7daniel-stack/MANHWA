"""MANHWA: builds app/src/main/assets/english_words.bloom, a Bloom filter of the English word list
(github.com/dwyl/english-words, words_alpha.txt, public domain / Unlicense). The app uses it to tell a line the OCR
misread ("Nimgng flmlvs") from real words, and reads such a bubble again from its image.

Usage: python3 tools/build_word_filter.py words_alpha.txt app/src/main/assets/english_words.bloom"""
import math
import struct
import sys

MASK = (1 << 64) - 1


def fnv(word: str, seed: int) -> int:
    h = (0xCBF29CE484222325 ^ seed) & MASK
    for ch in word.encode("utf-8"):
        h ^= ch
        h = (h * 0x100000001B3) & MASK
    return h


def main(src, out):
    words = sorted({w.strip().lower() for w in open(src, encoding="utf-8") if w.strip()})
    n = len(words)
    m = int(-n * math.log(0.01) / (math.log(2) ** 2))
    m = (m + 7) // 8 * 8
    k = 7
    bits = bytearray(m // 8)
    for w in words:
        h1 = fnv(w, 0)
        h2 = fnv(w, 0x9E3779B97F4A7C15) | 1
        for i in range(k):
            idx = ((h1 + i * h2) & MASK) % m
            bits[idx >> 3] |= 1 << (idx & 7)
    with open(out, "wb") as f:
        f.write(b"MWBF")
        f.write(struct.pack(">II", m, k))
        f.write(bits)
    print(n, "words,", m // 8, "bytes")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
