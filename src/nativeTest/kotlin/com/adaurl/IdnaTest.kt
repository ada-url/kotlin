package com.adaurl

import kotlin.test.*

class IdnaTest {
    @Test
    fun toUnicode() {
        assertEquals("meßagefactory.ca", Idna.toUnicode("xn--meagefactory-m9a.ca"))
    }

    @Test
    fun toAscii() {
        assertEquals("xn--meagefactory-m9a.ca", Idna.toAscii("meßagefactory.ca"))
    }

    @Test
    fun roundtrip() {
        val unicode = "三十六計.org"
        val ascii = Idna.toAscii(unicode)
        assertTrue(ascii.startsWith("xn--"))
        assertEquals(unicode, Idna.toUnicode(ascii))
    }

    @Test
    fun asciiDomainUnchanged() {
        assertEquals("example.com", Idna.toAscii("example.com"))
        assertEquals("example.com", Idna.toUnicode("example.com"))
    }
}
