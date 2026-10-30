package com.easycast.tv

private val schemePrefix = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")

/**
 * Reduces an HTTP request target to the form "/path?query".
 *
 * Normally a request line is `GET /api/state HTTP/1.1`, but clients configured to use a proxy send the
 * absolute form `GET http://192.168.1.3:8200/api/state HTTP/1.0`, and some tools leave out the scheme
 * (`GET 192.168.1.3:8200/ HTTP/1.0`). The host part means nothing to us (we are the host), so drop it.
 */
fun originForm(target: String): String {
    if (target.startsWith("/")) return target // already origin form; may contain "://" inside the query

    val scheme = schemePrefix.find(target)
    val rest = if (scheme != null) target.substring(scheme.value.length) else target
    // rest is now "host[:port][/path][?query]"
    val slash = rest.indexOf('/')
    val query = rest.indexOf('?')
    return when {
        slash >= 0 && (query < 0 || slash < query) -> rest.substring(slash)
        query >= 0 -> "/" + rest.substring(query)
        else -> "/"
    }
}
