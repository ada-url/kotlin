package com.adaurl

import adaC.*
import kotlinx.cinterop.*

// Ada's C API takes `(const char* input, size_t length)` where length is the
// UTF-8 byte count. Kotlin's String.length is UTF-16 code units, which differs
// for any character outside the Basic Multilingual Plane. Use this extension
// to get the correct byte count.
internal val String.utf8ByteSize: ULong get() = encodeToByteArray().size.toULong()

/**
 * Defines the type of the host in a parsed URL.
 */
@OptIn(ExperimentalForeignApi::class)
enum class HostType(val value: UByte) {
    Domain(0u),
    IPv4(1u),
    IPv6(2u),
    ;

    companion object {
        fun from(value: UByte): HostType = entries.firstOrNull { it.value == value } ?: Domain
    }
}

/**
 * Defines the scheme type of a parsed URL.
 */
@OptIn(ExperimentalForeignApi::class)
enum class SchemeType(val value: UByte) {
    Http(0u),
    NotSpecial(1u),
    Https(2u),
    Ws(3u),
    Ftp(4u),
    Wss(5u),
    File(6u),
    ;

    companion object {
        fun from(value: UByte): SchemeType = entries.firstOrNull { it.value == value } ?: NotSpecial
    }
}

/**
 * Serialization-free representation of URL components using 32-bit integer offsets.
 *
 * ```
 * https://user:pass@example.com:1234/foo/bar?baz#quux
 *       |     |    |          | ^^^^|       |   |
 *       |     |    |          | |   |       |   `----- hashStart
 *       |     |    |          | |   |       `--------- searchStart
 *       |     |    |          | |   `----------------- pathnameStart
 *       |     |    |          | `--------------------- port
 *       |     |    |          `----------------------- hostEnd
 *       |     |    `---------------------------------- hostStart
 *       |     `--------------------------------------- usernameEnd
 *       `--------------------------------------------- protocolEnd
 * ```
 */
data class UrlComponents(
    val protocolEnd: UInt,
    val usernameEnd: UInt,
    val hostStart: UInt,
    val hostEnd: UInt,
    /** `null` when port is omitted (`ada_url_omitted` = 0xffffffff) */
    val port: UInt?,
    val pathnameStart: UInt?,
    val searchStart: UInt?,
    val hashStart: UInt?,
)

/**
 * A parsed URL conforming to the [WHATWG URL specification](https://url.spec.whatwg.org/).
 *
 * Wraps the Ada C++ URL parser via its C API. Memory for the underlying native object is
 * managed by this class; call [close] (or use `use {}`) when you are done.
 */
@OptIn(ExperimentalForeignApi::class)
class Url private constructor(private val ptr: COpaquePointer) : AutoCloseable {
    override fun close() {
        ada_free(ptr)
    }

    /** Returns `true` when the URL was successfully parsed. */
    val isValid: Boolean get() = ada_is_valid(ptr)

    // ── String helper ──────────────────────────────────────────────────────────

    // Read exactly `length` UTF-8 bytes and decode as a Kotlin String.
    // ada_string.length is a byte count; readBytes ensures we read the right amount
    // without depending on a null terminator.
    private fun CValue<ada_string>.toKString(): String =
        useContents {
            if (length == 0uL) "" else data!!.readBytes(length.toInt()).decodeToString()
        }

    private fun CValue<ada_owned_string>.toKStringAndFree(): String {
        val result =
            useContents {
                if (length == 0uL) "" else data!!.readBytes(length.toInt()).decodeToString()
            }
        ada_free_owned_string(this)
        return result
    }

    // ── Getters ────────────────────────────────────────────────────────────────

    /**
     * The serialized form of the URL (e.g. `"https://example.com/"`).
     *
     * Points into the URL object's internal buffer; valid for the lifetime of this [Url].
     */
    val href: String get() = ada_get_href(ptr).toKString()

    /**
     * The origin of the URL (e.g. `"https://example.com"`).
     * Allocates a new string; freed immediately after conversion.
     */
    val origin: String get() = ada_get_origin(ptr).toKStringAndFree()

    val username: String get() = ada_get_username(ptr).toKString()
    val password: String get() = ada_get_password(ptr).toKString()
    val port: String get() = ada_get_port(ptr).toKString()
    val hash: String get() = ada_get_hash(ptr).toKString()
    val host: String get() = ada_get_host(ptr).toKString()
    val hostname: String get() = ada_get_hostname(ptr).toKString()
    val pathname: String get() = ada_get_pathname(ptr).toKString()
    val search: String get() = ada_get_search(ptr).toKString()
    val protocol: String get() = ada_get_protocol(ptr).toKString()

    val hostType: HostType get() = HostType.from(ada_get_host_type(ptr))
    val schemeType: SchemeType get() = SchemeType.from(ada_get_scheme_type(ptr))

    val components: UrlComponents
        get() {
            val c = ada_get_components(ptr)!!.pointed
            return UrlComponents(
                protocolEnd = c.protocol_end,
                usernameEnd = c.username_end,
                hostStart = c.host_start,
                hostEnd = c.host_end,
                port = c.port.takeUnless { it == ada_url_omitted },
                pathnameStart = c.pathname_start.takeUnless { it == ada_url_omitted },
                searchStart = c.search_start.takeUnless { it == ada_url_omitted },
                hashStart = c.hash_start.takeUnless { it == ada_url_omitted },
            )
        }

    // ── Setters ────────────────────────────────────────────────────────────────
    // cinterop maps const char* params annotated with @CCall.CString as String?.
    // We pass the UTF-8 byte length (not UTF-16 code-unit count) as required by Ada.

    /** @return `true` on success */
    fun setHref(input: String): Boolean = ada_set_href(ptr, input, input.utf8ByteSize)

    /** @return `true` on success */
    fun setUsername(input: String): Boolean = ada_set_username(ptr, input, input.utf8ByteSize)

    /** @return `true` on success */
    fun setPassword(input: String): Boolean = ada_set_password(ptr, input, input.utf8ByteSize)

    /** Pass `null` to clear the port. @return `true` on success */
    fun setPort(input: String?): Boolean {
        if (input == null) {
            ada_clear_port(ptr)
            return true
        }
        return ada_set_port(ptr, input, input.utf8ByteSize)
    }

    /** Pass `null` to clear the hash. */
    fun setHash(input: String?) {
        if (input == null) {
            ada_clear_hash(ptr)
        } else {
            ada_set_hash(ptr, input, input.utf8ByteSize)
        }
    }

    /** @return `true` on success */
    fun setHost(input: String): Boolean = ada_set_host(ptr, input, input.utf8ByteSize)

    /** @return `true` on success */
    fun setHostname(input: String): Boolean = ada_set_hostname(ptr, input, input.utf8ByteSize)

    /** @return `true` on success */
    fun setPathname(input: String): Boolean = ada_set_pathname(ptr, input, input.utf8ByteSize)

    /** Pass `null` to clear the search string. */
    fun setSearch(input: String?) {
        if (input == null) {
            ada_clear_search(ptr)
        } else {
            ada_set_search(ptr, input, input.utf8ByteSize)
        }
    }

    /** @return `true` on success */
    fun setProtocol(input: String): Boolean = ada_set_protocol(ptr, input, input.utf8ByteSize)

    // ── Predicates ─────────────────────────────────────────────────────────────

    fun hasCredentials(): Boolean = ada_has_credentials(ptr)

    fun hasEmptyHostname(): Boolean = ada_has_empty_hostname(ptr)

    fun hasHostname(): Boolean = ada_has_hostname(ptr)

    fun hasNonEmptyUsername(): Boolean = ada_has_non_empty_username(ptr)

    fun hasNonEmptyPassword(): Boolean = ada_has_non_empty_password(ptr)

    fun hasPort(): Boolean = ada_has_port(ptr)

    fun hasPassword(): Boolean = ada_has_password(ptr)

    fun hasHash(): Boolean = ada_has_hash(ptr)

    fun hasSearch(): Boolean = ada_has_search(ptr)

    // ── Standard overrides ─────────────────────────────────────────────────────

    override fun toString(): String = href

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Url) return false
        return href == other.href
    }

    override fun hashCode(): Int = href.hashCode()

    /** Returns an independent deep copy of this URL. The caller owns the result. */
    fun copy(): Url = Url(ada_copy(ptr)!!)

    companion object {
        /**
         * Parses [input], optionally relative to [base].
         *
         * @return a valid [Url] or `null` when parsing fails.
         */
        fun parse(
            input: String,
            base: String? = null,
        ): Url? {
            val ptr =
                if (base != null) {
                    ada_parse_with_base(input, input.utf8ByteSize, base, base.utf8ByteSize)
                } else {
                    ada_parse(input, input.utf8ByteSize)
                } ?: return null

            val url = Url(ptr)
            return if (url.isValid) {
                url
            } else {
                url.close()
                null
            }
        }

        /**
         * Returns `true` if [input] can be parsed as a valid URL, optionally relative to [base].
         */
        fun canParse(
            input: String,
            base: String? = null,
        ): Boolean =
            if (base != null) {
                ada_can_parse_with_base(input, input.utf8ByteSize, base, base.utf8ByteSize)
            } else {
                ada_can_parse(input, input.utf8ByteSize)
            }
    }
}
