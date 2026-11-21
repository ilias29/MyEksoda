package io.eksoda.engine.providers.s1ecos

import io.eksoda.engine.canonical.CanonicalReceipt
import io.eksoda.engine.providers.Layout
import io.eksoda.engine.providers.UrlIdentity
import io.eksoda.engine.providers.impact.ImpactLayoutV1
import io.eksoda.engine.providers.impact.Site
import io.eksoda.engine.providers.impact.parsePage

// s1ecos receipt page → CanonicalReceipt: Impact's page, read by Impact's layouts under this
// provider's code and parser version. The page prints the receipt's /p/ link, which a /v/ link
// matches by issuer and access GUID ([printsLink]).

public const val PARSER_VERSION: String = "s1ecos/1"

private val SITE = Site(PROVIDER_CODE, PARSER_VERSION, ::printsLink)

/** Newest first. */
public val LAYOUTS: List<Layout> = listOf(ImpactLayoutV1(SITE))

public fun parseHtml(html: String, identity: UrlIdentity): CanonicalReceipt =
    parsePage(html, identity, LAYOUTS)
