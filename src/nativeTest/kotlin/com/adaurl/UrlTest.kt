package com.adaurl

import kotlin.test.*

class UrlTest {
    @Test
    fun shouldDisplaySerialization() {
        val cases =
            listOf(
                "http://example.com/" to "http://example.com/",
                "HTTP://EXAMPLE.COM" to "http://example.com/",
                "http://user:pwd@domain.com" to "http://user:pwd@domain.com/",
                "HTTP://EXAMPLE.COM/FOO/BAR?K1=V1&K2=V2" to "http://example.com/FOO/BAR?K1=V1&K2=V2",
                "http://example.com/🦀/❤️/" to "http://example.com/%F0%9F%A6%80/%E2%9D%A4%EF%B8%8F/",
                "https://example.org/hello world.html" to "https://example.org/hello%20world.html",
                "https://三十六計.org/走為上策/" to "https://xn--ehq95fdxbx86i.org/%E8%B5%B0%E7%82%BA%E4%B8%8A%E7%AD%96/",
            )
        for ((input, expected) in cases) {
            val url = Url.parse(input)
            assertNotNull(url, "Failed to parse: $input")
            url.use { assertEquals(expected, it.href, "href mismatch for: $input") }
        }
    }

    @Test
    fun canParse() {
        assertTrue(Url.canParse("https://google.com"))
        assertTrue(Url.canParse("/hello", "https://www.google.com"))
        assertFalse(Url.canParse("this is not a url"))
        assertFalse(Url.canParse(""))
    }

    @Test
    fun parseReturnsNullForInvalid() {
        assertNull(Url.parse("this is not a url"))
        assertNull(Url.parse(""))
    }

    @Test
    fun shouldParseSimpleUrl() {
        val url = Url.parse("https://username:password@google.com:9090/search?query#hash")
        assertNotNull(url)
        url.use { u ->
            assertEquals("https://username:password@google.com:9090/search?query#hash", u.href)
            assertEquals(SchemeType.Https, u.schemeType)
            assertEquals(HostType.Domain, u.hostType)
            assertEquals("https://google.com:9090", u.origin)
            assertEquals("username", u.username)
            assertEquals("password", u.password)
            assertEquals("9090", u.port)
            assertEquals("#hash", u.hash)
            assertEquals("google.com:9090", u.host)
            assertEquals("google.com", u.hostname)
            assertEquals("/search", u.pathname)
            assertEquals("?query", u.search)
            assertEquals("https:", u.protocol)

            assertTrue(u.hasCredentials())
            assertTrue(u.hasNonEmptyUsername())
            assertTrue(u.hasNonEmptyPassword())
            assertTrue(u.hasPassword())
            assertTrue(u.hasPort())
            assertTrue(u.hasSearch())
            assertTrue(u.hasHash())
            assertTrue(u.hasHostname())
        }
    }

    @Test
    fun setters() {
        val url = Url.parse("https://username:password@google.com:9090/search?query#hash")
        assertNotNull(url)
        url.use { u ->
            assertTrue(u.setUsername("new-username"))
            assertEquals("new-username", u.username)

            assertTrue(u.setPassword("new-password"))
            assertEquals("new-password", u.password)

            assertTrue(u.setPort("4242"))
            assertEquals("4242", u.port)

            assertTrue(u.setPort(null))
            assertEquals("", u.port)
            assertFalse(u.hasPort())

            u.setHash("#new-hash")
            assertEquals("#new-hash", u.hash)

            u.setHash(null)
            assertFalse(u.hasHash())

            assertTrue(u.setHost("yagiz.co:9999"))
            assertEquals("yagiz.co:9999", u.host)

            assertTrue(u.setHostname("domain.com"))
            assertEquals("domain.com", u.hostname)

            assertTrue(u.setPathname("/new-search"))
            assertEquals("/new-search", u.pathname)

            u.setSearch("updated-query")
            assertEquals("?updated-query", u.search)

            u.setSearch(null)
            assertFalse(u.hasSearch())

            assertTrue(u.setProtocol("wss"))
            assertEquals("wss:", u.protocol)
            assertEquals(SchemeType.Wss, u.schemeType)

            assertTrue(u.setHref("https://lemire.me"))
            assertEquals("https://lemire.me/", u.href)
        }
    }

    @Test
    fun schemeTypes() {
        val cases =
            listOf(
                "file:///foo/bar" to SchemeType.File,
                "ws://example.com/ws" to SchemeType.Ws,
                "wss://example.com/wss" to SchemeType.Wss,
                "ftp://example.com/file.txt" to SchemeType.Ftp,
                "http://example.com/file.txt" to SchemeType.Http,
                "https://example.com/file.txt" to SchemeType.Https,
                "foo://example.com" to SchemeType.NotSpecial,
            )
        for ((input, expected) in cases) {
            val url = Url.parse(input)
            assertNotNull(url, "failed to parse: $input")
            url.use { assertEquals(expected, it.schemeType, "scheme mismatch for $input") }
        }
    }

    @Test
    fun shouldCompareUrls() {
        val cases =
            listOf(
                Triple("http://example.com/", "http://example.com/", true),
                Triple("http://example.com/", "https://example.com/", false),
                Triple("https://user:pwd@example.com", "https://user:pwd@example.com", true),
            )
        for ((left, right, expected) in cases) {
            val l = Url.parse(left)!!
            val r = Url.parse(right)!!
            assertEquals(expected, l == r, "equality mismatch: $left vs $right")
            l.close()
            r.close()
        }
    }

    @Test
    fun shouldHandleEmptyHost() {
        // https://github.com/ada-url/rust/issues/74
        val url = Url.parse("file:///C:/Users/User/Documents/example.pdf")
        assertNotNull(url)
        url.use {
            assertEquals("", it.host)
            assertEquals("", it.hostname)
            assertTrue(it.hasEmptyHostname())
        }
    }

    @Test
    fun shouldClone() {
        val first = Url.parse("https://lemire.me")!!
        val second = first.copy()
        second.setHref("https://yagiz.co")
        assertNotEquals(first.href, second.href)
        assertEquals("https://lemire.me/", first.href)
        assertEquals("https://yagiz.co/", second.href)
        first.close()
        second.close()
    }

    @Test
    fun toStringReturnsHref() {
        val url = Url.parse("https://example.com/path?q=1#frag")!!
        url.use {
            assertEquals(it.href, it.toString())
        }
    }

    @Test
    fun urlComponents() {
        val url = Url.parse("https://user:pass@example.com:1234/foo/bar?baz#quux")!!
        url.use {
            val c = it.components
            assertEquals(6u, c.protocolEnd) // "https:"
            assertEquals(1234u, c.port)
            assertNotNull(c.searchStart)
            assertNotNull(c.hashStart)
        }
    }

    @Test
    fun relativeUrlParsing() {
        val url = Url.parse("/pathname", "https://ada-url.github.io/ada")
        assertNotNull(url)
        url.use {
            assertEquals("https://ada-url.github.io/pathname", it.href)
        }
    }

    @Test
    fun ipv4HostType() {
        val url = Url.parse("https://127.0.0.1:8080/index.html")!!
        url.use {
            assertEquals(HostType.IPv4, it.hostType)
            assertEquals("127.0.0.1:8080", it.host)
            assertEquals("127.0.0.1", it.hostname)
        }
    }

    @Test
    fun ipv6HostType() {
        val url = Url.parse("https://[::1]/index.html")!!
        url.use {
            assertEquals(HostType.IPv6, it.hostType)
        }
    }
}
