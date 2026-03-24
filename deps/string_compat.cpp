/**
 * Compatibility shim providing std::string::_M_replace_cold.
 *
 * This symbol is emitted as an undefined reference when ada.cpp is compiled
 * with GCC 13+ (it splits the cold path out of std::string::_M_replace as a
 * separate function). The Kotlin/Native bundled libstdc++ is too old to
 * define it, so we provide it here inside libada.a.
 *
 * The mangled name differs by platform because size_t mangles differently:
 *   Linux x86-64:  size_t = unsigned long  → 'm'
 *   Windows mingw: size_t = unsigned long long → 'y'
 *
 * We use an asm label on each platform to bind the correct mangled name
 * rather than relying on template instantiation, which would require the
 * full libstdc++ headers and would itself reference the cold symbol again.
 */
#include <cstring>

static void smove(char* d, const char* s, unsigned long long n) {
    if (n) __builtin_memmove(d, s, static_cast<__SIZE_TYPE__>(n));
}

static void scopy(char* d, const char* s, unsigned long long n) {
    if (n) __builtin_memcpy(d, s, static_cast<__SIZE_TYPE__>(n));
}

// Select the correct mangled name for the target platform.
#if defined(_WIN32) || defined(__MINGW32__)
// mingw: size_t = unsigned long long → mangled as 'y'
#  define ADA_REPLACE_COLD_SYM \
    "_ZNSt7__cxx1112basic_stringIcSt11char_traitsIcESaIcEE15_M_replace_coldEPcyPKcyy"
#else
// Linux/macOS: size_t = unsigned long → mangled as 'm'
#  define ADA_REPLACE_COLD_SYM \
    "_ZNSt7__cxx1112basic_stringIcSt11char_traitsIcESaIcEE15_M_replace_coldEPcmPKcmm"
#endif

void _ada_string_replace_cold_impl(
    char*              __p,
    unsigned long long __len1,
    const char*        __s,
    unsigned long long __len2,
    unsigned long long __how_much)
    __asm__(ADA_REPLACE_COLD_SYM);

__attribute__((__noinline__, __noclone__, __cold__))
void _ada_string_replace_cold_impl(
    char*              __p,
    unsigned long long __len1,
    const char*        __s,
    unsigned long long __len2,
    unsigned long long __how_much)
{
    if (__len2 && __len2 <= __len1)
        smove(__p, __s, __len2);
    if (__how_much && __len1 != __len2)
        smove(__p + __len2, __p + __len1, __how_much);
    if (__len2 > __len1) {
        if (__s + __len2 <= __p + __len1)
            smove(__p, __s, __len2);
        else if (__s >= __p + __len1) {
            unsigned long long __poff = (__s - __p) + (__len2 - __len1);
            scopy(__p, __p + __poff, __len2);
        } else {
            unsigned long long __nleft = (__p + __len1) - __s;
            smove(__p, __s, __nleft);
            scopy(__p + __nleft, __p + __len2, __len2 - __nleft);
        }
    }
}
