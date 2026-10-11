package io.github.big_sw_little_sw.folio.web

/**
 * The content security policy of each UI response (design 12.3). The layout loads only files that Folio serves, and
 * uses no inline scripts or styles. The UI filter chain sets it. The error pages set it too, because Spring Security
 * writes no headers on the container's error dispatch.
 */
const val CONTENT_SECURITY_POLICY = "default-src 'self'; frame-ancestors 'none'"
