/**
 * Compatibility shim providing std::string::_M_replace_cold for GCC 8.3's
 * libstdc++ (which Kotlin/Native statically links on Linux).
 *
 * This symbol is a cold-path helper introduced in newer libstdc++ (GCC 9+)
 * and is emitted as an undefined reference when ada.cpp is compiled with
 * GCC 13 or later. Compiled with the KN GCC 8.3 toolchain so it uses the
 * same ABI, this shim provides the definition in libada.a.
 *
 * The mangled name corresponds to:
 *   std::__cxx11::basic_string<char, std::char_traits<char>, std::allocator<char>>
 *       ::_M_replace_cold(char*, size_t, const char*, size_t, size_t)
 */

// Use asm label to set the exact mangled symbol name so it matches what
// the linker expects when resolving references from ada.o.
void _ada_string_replace_cold_impl(
    char*         __p,
    unsigned long __len1,
    const char*   __s,
    unsigned long __len2,
    unsigned long __how_much)
    __asm__("_ZNSt7__cxx1112basic_stringIcSt11char_traitsIcESaIcEE15_M_replace_coldEPcmPKcmm");

__attribute__((__noinline__, __noclone__, __cold__))
void _ada_string_replace_cold_impl(
    char*         __p,
    unsigned long __len1,
    const char*   __s,
    unsigned long __len2,
    unsigned long __how_much)
{
    auto smove = [](char* d, const char* s, unsigned long n) noexcept {
        if (n) __builtin_memmove(d, s, n);
    };
    auto scopy = [](char* d, const char* s, unsigned long n) noexcept {
        if (n) __builtin_memcpy(d, s, n);
    };

    if (__len2 && __len2 <= __len1)
        smove(__p, __s, __len2);
    if (__how_much && __len1 != __len2)
        smove(__p + __len2, __p + __len1, __how_much);
    if (__len2 > __len1) {
        if (__s + __len2 <= __p + __len1)
            smove(__p, __s, __len2);
        else if (__s >= __p + __len1) {
            unsigned long __poff = (__s - __p) + (__len2 - __len1);
            scopy(__p, __p + __poff, __len2);
        } else {
            unsigned long __nleft = (__p + __len1) - __s;
            smove(__p, __s, __nleft);
            scopy(__p + __nleft, __p + __len2, __len2 - __nleft);
        }
    }
}
