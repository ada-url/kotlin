package com.adaurl

import adaC.*
import kotlinx.cinterop.*

/**
 * The version of the bundled Ada C++ library.
 */
data class AdaVersion(val major: Int, val minor: Int, val revision: Int) {
    override fun toString(): String = "$major.$minor.$revision"
}

/**
 * Returns the version string of the bundled Ada library (e.g. `"2.7.8"`).
 */
@OptIn(ExperimentalForeignApi::class)
fun adaVersion(): String = ada_get_version()?.toKString() ?: ""

/**
 * Returns the version of the bundled Ada library as a structured [AdaVersion].
 */
@OptIn(ExperimentalForeignApi::class)
fun adaVersionComponents(): AdaVersion =
    ada_get_version_components().useContents {
        AdaVersion(major, minor, revision)
    }
