package com.adaurl

import adaC.*
import kotlinx.cinterop.*

/**
 * IDNA (Internationalized Domain Names in Applications) helpers
 * via [Unicode Technical Standard #46](https://www.unicode.org/reports/tr46/).
 */
@OptIn(ExperimentalForeignApi::class)
object Idna {
    private fun CValue<ada_owned_string>.toKStringAndFree(): String {
        val result =
            useContents {
                if (length == 0uL) "" else data!!.readBytes(length.toInt()).decodeToString()
            }
        ada_free_owned_string(this)
        return result
    }

    /**
     * Converts a Punycode-encoded ACE label or domain to its Unicode representation.
     *
     * ```kotlin
     * Idna.toUnicode("xn--meagefactory-m9a.ca") // -> "meßagefactory.ca"
     * ```
     *
     * Returns an empty string if [input] is invalid.
     */
    fun toUnicode(input: String): String = ada_idna_to_unicode(input, input.utf8ByteSize).toKStringAndFree()

    /**
     * Converts a Unicode domain to its ACE (ASCII-Compatible Encoding) Punycode form.
     *
     * ```kotlin
     * Idna.toAscii("meßagefactory.ca") // -> "xn--meagefactory-m9a.ca"
     * ```
     *
     * Returns an empty string if [input] is invalid.
     */
    fun toAscii(input: String): String = ada_idna_to_ascii(input, input.utf8ByteSize).toKStringAndFree()
}
