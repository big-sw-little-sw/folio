package io.github.big_sw_little_sw.folio.web

/**
 * The content security policy of each UI response. The layout loads only files that Folio serves and uses no inline
 * scripts or styles, so the policy blocks all other sources. The UI filter chain and the error pages set the policy.
 */
const val CONTENT_SECURITY_POLICY = "default-src 'self'; frame-ancestors 'none'"
