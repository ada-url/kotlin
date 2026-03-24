package com.adaurl

import kotlin.test.*

class UrlSearchParamsTest {
    @Test
    fun parseAndGet() {
        UrlSearchParams.parse("a=1&b=2").use { params ->
            assertEquals("1", params.get("a"))
            assertEquals("2", params.get("b"))
            assertNull(params.get("c"))
            assertEquals(2, params.size)
            assertFalse(params.isEmpty)
        }
    }

    @Test
    fun set() {
        UrlSearchParams.parse("a=1&b=2").use { params ->
            params.set("a", "3")
            assertEquals("3", params.get("a"))
            assertEquals(2, params.size)
        }
    }

    @Test
    fun append() {
        UrlSearchParams.parse("a=1").use { params ->
            params.append("a", "2")
            assertEquals(2, params.size)
            params.getAll("a").use { all ->
                assertEquals(2, all.size)
                assertEquals("1", all[0])
                assertEquals("2", all[1])
            }
        }
    }

    @Test
    fun removeKey() {
        UrlSearchParams.parse("a=1&b=2").use { params ->
            params.remove("a")
            assertNull(params.get("a"))
            assertEquals(1, params.size)
        }
    }

    @Test
    fun removeKeyValue() {
        UrlSearchParams.parse("a=1&a=2&b=3").use { params ->
            params.remove("a", "1")
            // "a=2" should still be present
            assertEquals("2", params.get("a"))
        }
    }

    @Test
    fun has() {
        UrlSearchParams.parse("a=1&b=2").use { params ->
            assertTrue(params.has("a"))
            assertTrue(params.has("a", "1"))
            assertFalse(params.has("a", "99"))
            assertFalse(params.has("z"))
        }
    }

    @Test
    fun sort() {
        UrlSearchParams.parse("b=2&a=1").use { params ->
            params.sort()
            val keys = params.keys()
            assertEquals("a", keys.next())
            assertEquals("b", keys.next())
            assertFalse(keys.hasNext())
            keys.close()
        }
    }

    @Test
    fun toString_() {
        UrlSearchParams.parse("a=1&b=2").use { params ->
            assertEquals("a=1&b=2", params.toString())
        }
    }

    @Test
    fun keysIterator() {
        UrlSearchParams.parse("a=1&b=2").use { params ->
            val keys = params.keys()
            val collected = mutableListOf<String>()
            while (keys.hasNext()) collected += keys.next()
            keys.close()
            assertEquals(listOf("a", "b"), collected)
        }
    }

    @Test
    fun valuesIterator() {
        UrlSearchParams.parse("a=1&b=2").use { params ->
            val values = params.values()
            val collected = mutableListOf<String>()
            while (values.hasNext()) collected += values.next()
            values.close()
            assertEquals(listOf("1", "2"), collected)
        }
    }

    @Test
    fun entriesIterator() {
        UrlSearchParams.parse("a=1&b=2").use { params ->
            val entries = params.entries()
            val collected = mutableListOf<Pair<String, String>>()
            while (entries.hasNext()) collected += entries.next()
            entries.close()
            assertEquals(listOf("a" to "1", "b" to "2"), collected)
        }
    }

    @Test
    fun getAllList() {
        UrlSearchParams.parse("a=1&a=2&a=3").use { params ->
            params.getAll("a").use { list ->
                assertEquals(3, list.size)
                assertEquals(listOf("1", "2", "3"), list.toList())
                assertNull(list[3])
                assertNull(list[-1])
            }
        }
    }

    @Test
    fun emptyParams() {
        UrlSearchParams.parse("").use { params ->
            assertEquals(0, params.size)
            assertTrue(params.isEmpty)
            assertEquals("", params.toString())
        }
    }

    @Test
    fun specialCharacters() {
        UrlSearchParams.parse("q=hello+world&name=J%C3%B6rg").use { params ->
            assertEquals("hello world", params.get("q"))
            assertEquals("Jörg", params.get("name"))
        }
    }

    @Test
    fun reset() {
        UrlSearchParams.parse("a=1&b=2").use { params ->
            assertEquals(2, params.size)
            params.reset("x=9&y=8&z=7")
            assertEquals(3, params.size)
            assertEquals("9", params.get("x"))
            assertNull(params.get("a"))
        }
    }
}
