package io.eksoda.engine.merchants

import io.eksoda.engine.text.normalizeText

// Supermarket chains the app shows a logo for, recognized by the issuer's VAT number.
//
// A name match alone is not used: Μασούτης, Βασιλόπουλος and Σκλαβενίτης are also common
// surnames, so a small shop of that name would get a chain's logo. A franchise store has its own
// VAT number and keeps its initials. The VAT numbers are the issuers of real receipts
// (testdata/fixtures).

/** A chain with a logo in the app. */
public enum class Brand {
    AB_VASILOPOULOS,
    SKLAVENITIS,
    MASOUTIS,
    LIDL,
    MY_MARKET,
}

/**
 * A chain's VAT number. [sharedWith] is set when another chain issues receipts under the same
 * number; the logo then follows the household's name for the merchant (see [merchantBrand]).
 */
private class Chain(val brand: Brand, val name: String, val sharedWith: String? = null)

private const val GREECE = "GR"

private val CHAINS_BY_VAT: Map<String, Chain> = mapOf(
    // ΑΛΦΑ ΒΗΤΑ ΒΑΣΙΛΟΠΟΥΛΟΣ ΜΟΝΟΠΡΟΣΩΠΗ Α.Ε. (aade_fhm).
    "094025817" to Chain(Brand.AB_VASILOPOULOS, "AB"),
    // ΕΛΛΗΝΙΚΕΣ ΥΠΕΡΑΓΟΡΕΣ ΣΚΛΑΒΕΝΙΤΗΣ (epsilon).
    "800764388" to Chain(Brand.SKLAVENITIS, "ΣΚΛΑΒΕΝΙΤΗΣ"),
    // ΔΙΑΜΑΝΤΗΣ ΜΑΣΟΥΤΗΣ Α.Ε. (epsilon).
    "094247924" to Chain(Brand.MASOUTIS, "ΜΑΣΟΥΤΗΣ"),
    // LIDL ΕΛΛΑΣ (entersoft).
    "093683423" to Chain(Brand.LIDL, "LIDL"),
    // METRO ΑΕΒΕ (impact) runs My Market and Metro Cash & Carry under one VAT number, and the
    // receipts do not tell them apart. Households shop at My Market, so the number shows its logo
    // until the household renames the merchant to something else (e.g. "Metro Cash & Carry").
    "094062259" to Chain(Brand.MY_MARKET, "MY MARKET", sharedWith = "Metro Cash & Carry"),
)

/** Compared without case, accents or spaces: "My Market", "mymarket" and "MY MARKET" match. */
private fun nameKey(text: String): String = normalizeText(text).replace(" ", "")

/**
 * The chain a merchant belongs to, from its country and VAT number; null for any other shop.
 * For a VAT number two chains share, a name the household gave the merchant ([nameSetByUser])
 * must still name the chain, or the merchant shows its initials.
 */
public fun merchantBrand(
    countryCode: String,
    vatNumber: String,
    displayName: String,
    nameSetByUser: Boolean,
): Brand? {
    val chain = CHAINS_BY_VAT[vatNumber.trim()]?.takeIf { countryCode == GREECE } ?: return null
    val renamedAway = chain.sharedWith != null && nameSetByUser &&
        nameKey(chain.name) !in nameKey(displayName)
    return chain.brand.takeUnless { renamedAway }
}
