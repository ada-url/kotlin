package com.adaurl

import adaC.*
import kotlinx.cinterop.*

/**
 * Represents the [URLSearchParams](https://url.spec.whatwg.org/#interface-urlsearchparams)
 * interface from the WHATWG URL specification.
 *
 * Memory for the underlying native object is managed by this class; call [close]
 * (or use `use {}`) when you are done.
 */
@OptIn(ExperimentalForeignApi::class)
class UrlSearchParams private constructor(
    private val ptr: COpaquePointer,
) : AutoCloseable {
    override fun close() {
        ada_free_search_params(ptr)
    }

    // ── String helper ──────────────────────────────────────────────────────────

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

    // ── Properties ─────────────────────────────────────────────────────────────

    /** The number of key/value pairs. */
    val size: Int get() = ada_search_params_size(ptr).toInt()

    /** Returns `true` when there are no entries. */
    val isEmpty: Boolean get() = size == 0

    // ── Mutating methods ───────────────────────────────────────────────────────

    /** Sorts all key/value pairs in place by key, in Unicode code-point order. */
    fun sort() {
        ada_search_params_sort(ptr)
    }

    /** Appends a new key/value pair without removing any existing pair with the same key. */
    fun append(
        key: String,
        value: String,
    ) {
        ada_search_params_append(ptr, key, key.utf8ByteSize, value, value.utf8ByteSize)
    }

    /**
     * Sets [key] to [value], removing all pre-existing pairs that have the same key.
     */
    fun set(
        key: String,
        value: String,
    ) {
        ada_search_params_set(ptr, key, key.utf8ByteSize, value, value.utf8ByteSize)
    }

    /** Removes all pairs whose key equals [key]. */
    fun remove(key: String) {
        ada_search_params_remove(ptr, key, key.utf8ByteSize)
    }

    /** Removes all pairs whose key equals [key] and value equals [value]. */
    fun remove(
        key: String,
        value: String,
    ) {
        ada_search_params_remove_value(ptr, key, key.utf8ByteSize, value, value.utf8ByteSize)
    }

    /**
     * Replaces the entire contents of this [UrlSearchParams] by re-parsing [input].
     *
     * Equivalent to the [URLSearchParams](https://url.spec.whatwg.org/) constructor
     * called on an existing instance.
     */
    fun reset(input: String) {
        ada_search_params_reset(ptr, input, input.utf8ByteSize)
    }

    // ── Query methods ──────────────────────────────────────────────────────────

    /** Returns `true` if there is at least one pair with the given [key]. */
    fun has(key: String): Boolean = ada_search_params_has(ptr, key, key.utf8ByteSize)

    /** Returns `true` if there is at least one pair with both the given [key] and [value]. */
    fun has(
        key: String,
        value: String,
    ): Boolean = ada_search_params_has_value(ptr, key, key.utf8ByteSize, value, value.utf8ByteSize)

    /**
     * Returns the first value associated with [key], or `null` if not found.
     */
    fun get(key: String): String? {
        val s = ada_search_params_get(ptr, key, key.utf8ByteSize)
        return s.useContents {
            if (data == null) {
                null
            } else if (length == 0uL) {
                ""
            } else {
                data!!.readBytes(length.toInt()).decodeToString()
            }
        }
    }

    /**
     * Returns all values associated with [key].
     *
     * The caller is responsible for closing the returned [UrlSearchParamsList].
     */
    fun getAll(key: String): UrlSearchParamsList {
        val strings = ada_search_params_get_all(ptr, key, key.utf8ByteSize)!!
        return UrlSearchParamsList(strings)
    }

    /**
     * Returns a forward-only iterator over all keys. Caller must close it when done.
     */
    fun keys(): UrlSearchParamsKeyIterator = UrlSearchParamsKeyIterator(ada_search_params_get_keys(ptr)!!)

    /**
     * Returns a forward-only iterator over all values. Caller must close it when done.
     */
    fun values(): UrlSearchParamsValueIterator = UrlSearchParamsValueIterator(ada_search_params_get_values(ptr)!!)

    /**
     * Returns a forward-only iterator over all key/value pairs. Caller must close it when done.
     */
    fun entries(): UrlSearchParamsEntryIterator = UrlSearchParamsEntryIterator(ada_search_params_get_entries(ptr)!!)

    /** Serializes the search params to `application/x-www-form-urlencoded` format. */
    override fun toString(): String = ada_search_params_to_string(ptr).toKStringAndFree()

    companion object {
        /**
         * Parses [input] as a URL query string and returns a new [UrlSearchParams].
         *
         * The input may optionally begin with `?`.
         */
        fun parse(input: String): UrlSearchParams =
            UrlSearchParams(ada_parse_search_params(input, input.utf8ByteSize)!!)
    }
}

// ── Helper collections / iterators ────────────────────────────────────────────

/**
 * An owned list of strings returned by [UrlSearchParams.getAll].
 * Must be closed after use.
 */
@OptIn(ExperimentalForeignApi::class)
class UrlSearchParamsList internal constructor(
    private val ptr: COpaquePointer,
) : AutoCloseable {
    val size: Int get() = ada_strings_size(ptr).toInt()
    val isEmpty: Boolean get() = size == 0

    /** Returns the string at [index], or `null` if out of bounds. */
    operator fun get(index: Int): String? {
        if (index < 0 || index >= size) return null
        return ada_strings_get(ptr, index.toULong()).useContents {
            if (length == 0uL) "" else data!!.readBytes(length.toInt()).decodeToString()
        }
    }

    /** Returns all entries as a [List]. */
    fun toList(): List<String> = (0 until size).mapNotNull { get(it) }

    override fun close() {
        ada_free_strings(ptr)
    }
}

/**
 * A forward-only iterator over search-param keys. Must be closed after use.
 */
@OptIn(ExperimentalForeignApi::class)
class UrlSearchParamsKeyIterator internal constructor(
    private val ptr: COpaquePointer,
) : Iterator<String>,
    AutoCloseable {
    override fun hasNext(): Boolean = ada_search_params_keys_iter_has_next(ptr)

    override fun next(): String =
        ada_search_params_keys_iter_next(ptr).useContents {
            if (length == 0uL) "" else data!!.readBytes(length.toInt()).decodeToString()
        }

    override fun close() {
        ada_free_search_params_keys_iter(ptr)
    }
}

/**
 * A forward-only iterator over search-param values. Must be closed after use.
 */
@OptIn(ExperimentalForeignApi::class)
class UrlSearchParamsValueIterator internal constructor(
    private val ptr: COpaquePointer,
) : Iterator<String>,
    AutoCloseable {
    override fun hasNext(): Boolean = ada_search_params_values_iter_has_next(ptr)

    override fun next(): String =
        ada_search_params_values_iter_next(ptr).useContents {
            if (length == 0uL) "" else data!!.readBytes(length.toInt()).decodeToString()
        }

    override fun close() {
        ada_free_search_params_values_iter(ptr)
    }
}

/**
 * A forward-only iterator over search-param key/value [Pair]s. Must be closed after use.
 */
@OptIn(ExperimentalForeignApi::class)
class UrlSearchParamsEntryIterator internal constructor(
    private val ptr: COpaquePointer,
) : Iterator<Pair<String, String>>,
    AutoCloseable {
    override fun hasNext(): Boolean = ada_search_params_entries_iter_has_next(ptr)

    override fun next(): Pair<String, String> =
        ada_search_params_entries_iter_next(ptr).useContents {
            val k = key.run { if (length == 0uL) "" else data!!.readBytes(length.toInt()).decodeToString() }
            val v = value.run { if (length == 0uL) "" else data!!.readBytes(length.toInt()).decodeToString() }
            k to v
        }

    override fun close() {
        ada_free_search_params_entries_iter(ptr)
    }
}
