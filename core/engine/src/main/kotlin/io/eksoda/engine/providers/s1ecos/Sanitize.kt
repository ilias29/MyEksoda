package io.eksoda.engine.providers.s1ecos

import io.eksoda.engine.providers.Provider
import io.eksoda.engine.providers.SanitizeResult
import io.eksoda.engine.providers.impact.sanitizeHtml as sanitizeImpactPage

// Removes personal data from a saved s1ecos receipt page: Impact's page, so Impact's sanitizer
// (which also knows the /v/ link and its not-found message).

public fun sanitizeHtml(rawHtml: String): SanitizeResult = sanitizeImpactPage(rawHtml)

/** s1ecos e-receipt host (einvoice.s1ecos.gr), serving Impact's receipt pages. */
public val S1ECOS: Provider = Provider(
    code = PROVIDER_CODE,
    hosts = setOf(HOST),
    layouts = LAYOUTS,
    recognize = ::recognize,
    parseHtml = ::parseHtml,
    sanitize = ::sanitizeHtml,
)
