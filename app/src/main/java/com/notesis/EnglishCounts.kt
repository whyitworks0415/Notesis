package com.notesis

/**
 * A count with its noun, "1 page" or "3 pages", for messages in the UI.
 * Irregular nouns pass their own [plural].
 */
internal fun countNoun(count: Int, singular: String, plural: String = singular + "s"): String =
    "$count ${if (count == 1) singular else plural}"
