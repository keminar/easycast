package com.easycast.tv

import org.junit.Assert.assertEquals
import org.junit.Test

class OriginFormTest {
    private fun check(target: String, expected: String) =
        assertEquals("target \"$target\"", expected, originForm(target))

    @Test
    fun normalOriginFormIsUnchanged() {
        check("/", "/")
        check("/api/state", "/api/state")
        check("/desc.xml?a=b", "/desc.xml?a=b")
        check("/ctl/AVTransport", "/ctl/AVTransport")
    }

    @Test
    fun absoluteFormWithSchemeLosesHostAndPort() {
        check("http://192.168.1.3:8200/", "/")
        check("http://192.168.1.3:8200/api/state", "/api/state")
        check("http://192.168.1.3:8200/desc.xml?x=1&y=2", "/desc.xml?x=1&y=2")
        check("HTTP://Example:8200/a/b", "/a/b")
        check("https://tv.local/api/play", "/api/play")
    }

    @Test
    fun absoluteFormWithoutPathMeansRoot() {
        check("http://192.168.1.3:8200", "/")
        check("http://192.168.1.3:8200?x=1", "/?x=1")
        check("192.168.1.3:8200", "/")
    }

    @Test
    fun hostWithoutSchemeLosesHostAndPort() {
        check("192.168.1.3:8200/", "/")
        check("192.168.1.3:8200/api/state", "/api/state")
        check("192.168.1.3:8200/desc.xml?a=b", "/desc.xml?a=b")
        check("tv.local/index.html", "/index.html")
    }

    @Test
    fun urlInsideTheQueryIsNotMistakenForAHost() {
        check("/redirect?u=http://x.example/y", "/redirect?u=http://x.example/y")
        check("http://192.168.1.3:8200/r?u=http://x.example/y", "/r?u=http://x.example/y")
    }
}
