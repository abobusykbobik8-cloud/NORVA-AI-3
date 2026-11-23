#include <cassert>
#include <cstdio>
#include <string>
#include "utf8_stream.h"

static const std::string FFFD = "\xEF\xBF\xBD";

int main() {
    { Utf8Stream s; assert(s.push("abc", 3) == "abc"); }

    { // "я" = D1 8F, разорван между токенами
        Utf8Stream s;
        assert(s.push("\xD1", 1) == "");
        assert(s.push("\x8F", 1) == "\xD1\x8F");
    }
    { // эмодзи (4 байта) по одному байту
        Utf8Stream s;
        const char e[] = "\xF0\x9F\x98\x80";
        assert(s.push(e + 0, 1) == "");
        assert(s.push(e + 1, 1) == "");
        assert(s.push(e + 2, 1) == "");
        assert(s.push(e + 3, 1) == std::string(e, 4));
    }
    { // текст перед оборванным символом не задерживается
        Utf8Stream s;
        assert(s.push("ok\xE2\x82", 4) == "ok");
        assert(s.push("\xAC", 1) == "\xE2\x82\xAC");  // евро
    }
    { Utf8Stream s; assert(s.push("\xFF", 1) == FFFD); }
    { // обрыв на flush
        Utf8Stream s;
        assert(s.push("\xE2\x82", 2) == "");
        assert(s.flush() == FFFD);
    }
    { // overlong C0 80
        Utf8Stream s;
        assert(s.push("\xC0\x80", 2) == FFFD + FFFD);
    }
    { // суррогат ED A0 80
        Utf8Stream s;
        assert(s.push("\xED\xA0\x80", 3) == FFFD + FFFD + FFFD);
    }
    { // начало многобайтной последовательности, за которым идёт ASCII
        Utf8Stream s;
        assert(s.push("\xE2\x82z", 3) == FFFD + "z");
    }
    std::puts("utf8_stream: OK");
    return 0;
}
