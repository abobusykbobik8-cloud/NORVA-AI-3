#pragma once
#include <cstddef>
#include <string>

// Накапливает байты токенов и отдаёт только полные корректные UTF-8 последовательности.
// Токен может обрывать многобайтный символ посередине; некорректные байты заменяются на U+FFFD,
// поэтому в Kotlin всегда уходит валидный UTF-8.
class Utf8Stream {
public:
    std::string push(const char* data, size_t n) {
        pending_.append(data, n);
        return drain(false);
    }
    std::string flush() { return drain(true); }

private:
    std::string pending_;

    static const char* replacement() { return "\xEF\xBF\xBD"; }

    static int seqLen(unsigned char b) {
        if (b < 0x80) return 1;
        if (b >= 0xC2 && b <= 0xDF) return 2;
        if (b >= 0xE0 && b <= 0xEF) return 3;
        if (b >= 0xF0 && b <= 0xF4) return 4;
        return 0;
    }
    static bool isCont(unsigned char b) { return (b & 0xC0) == 0x80; }

    std::string drain(bool final) {
        std::string out;
        size_t i = 0;
        const size_t n = pending_.size();
        while (i < n) {
            const unsigned char b = static_cast<unsigned char>(pending_[i]);
            const int len = seqLen(b);
            if (len == 0) { out += replacement(); i++; continue; }
            if (len == 1) { out += static_cast<char>(b); i++; continue; }
            size_t avail = 1;
            while (avail < static_cast<size_t>(len) && i + avail < n &&
                   isCont(static_cast<unsigned char>(pending_[i + avail]))) {
                avail++;
            }
            if (avail == static_cast<size_t>(len)) {
                const unsigned char b1 = static_cast<unsigned char>(pending_[i + 1]);
                const bool bad = (b == 0xE0 && b1 < 0xA0) || (b == 0xED && b1 >= 0xA0) ||
                                 (b == 0xF0 && b1 < 0x90) || (b == 0xF4 && b1 >= 0x90);
                if (bad) { out += replacement(); i++; continue; }
                out.append(pending_, i, static_cast<size_t>(len));
                i += static_cast<size_t>(len);
                continue;
            }
            if (i + avail == n && !final) break;  // хвост может достроиться следующим токеном
            out += replacement();
            i += avail;
        }
        pending_.erase(0, i);
        return out;
    }
};
