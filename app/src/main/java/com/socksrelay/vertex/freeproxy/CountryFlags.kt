package com.socksrelay.vertex.freeproxy

/**
 * Converts an ISO 3166-1 alpha-2 country code (e.g. "US") into its flag
 * emoji, using the standard trick: flag emoji are pairs of Unicode
 * "regional indicator symbol" code points, one per letter, each offset
 * from the ASCII letter by a fixed amount. No image assets needed — every
 * Android version in this app's minSdk range renders these natively.
 */
object CountryFlags {
    private const val REGIONAL_INDICATOR_OFFSET = 0x1F1E6 - 'A'.code

    fun flagEmoji(countryCode: String?): String {
        if (countryCode == null || countryCode.length != 2) return "\uD83C\uDFF3\uFE0F" // white flag fallback
        val upper = countryCode.uppercase()
        if (!upper.all { it in 'A'..'Z' }) return "\uD83C\uDFF3\uFE0F"
        return upper.map { char ->
            String(Character.toChars(char.code + REGIONAL_INDICATOR_OFFSET))
        }.joinToString("")
    }
}
