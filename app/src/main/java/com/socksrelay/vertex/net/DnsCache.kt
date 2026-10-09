package com.socksrelay.vertex.net

/**
 * Small in-memory cache for DNS answers, so repeated lookups (every page
 * load asks for the same handful of names over and over) don't each cost a
 * brand-new proxy connection + handshake + upstream round trip.
 *
 * Deliberately conservative — a wrong cached answer is worse than a slow
 * one:
 *  - Only successful (NOERROR) answers that actually contain records are
 *    cached. Errors, NXDOMAIN, empty answers and truncated replies are not.
 *  - Entries live for the SMALLEST TTL in the answer (never longer than
 *    [MAX_TTL_SECONDS]); answers with a TTL under [MIN_TTL_SECONDS] aren't
 *    cached at all.
 *  - On a hit, record TTLs are rewritten to the time remaining, the
 *    transaction ID is the new query's, and the question section is copied
 *    from the new query (apps that randomize upper/lower case in the name
 *    expect to see their own spelling echoed back).
 *  - Queries with unusual shapes (extra records, unknown EDNS layout) are
 *    simply not cached.
 *  - Cleared whenever a new tunnel is built, because a different proxy
 *    means a different exit location and therefore possibly different
 *    answers.
 */
object DnsCache {
    private const val MAX_ENTRIES = 512
    private const val MIN_TTL_SECONDS = 5L
    private const val MAX_TTL_SECONDS = 300L
    private const val TYPE_OPT = 41

    private class Entry(
        val response: ByteArray,
        val storedAtMs: Long,
        val ttlSeconds: Long,
        val ttlOffsets: IntArray,
        val questionEnd: Int
    )

    private val map = object : LinkedHashMap<String, Entry>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean =
            size > MAX_ENTRIES
    }

    fun clear() {
        synchronized(map) { map.clear() }
    }

    /** Returns a ready-to-send answer for [query], or null if there isn't a fresh cached one. */
    fun get(query: ByteArray): ByteArray? {
        val key = keyFor(query) ?: return null
        val entry = synchronized(map) { map[key] } ?: return null

        val ageSeconds = (System.currentTimeMillis() - entry.storedAtMs) / 1000L
        if (ageSeconds >= entry.ttlSeconds) {
            synchronized(map) { map.remove(key) }
            return null
        }

        val copy = entry.response.copyOf()
        // Same transaction ID as the new query.
        copy[0] = query[0]
        copy[1] = query[1]
        // Echo the new query's exact spelling of the question.
        if (query.size >= entry.questionEnd) {
            System.arraycopy(query, 12, copy, 12, entry.questionEnd - 12)
        }
        // Count the TTLs down by how long the answer has been cached.
        for (offset in entry.ttlOffsets) {
            val original = readInt(copy, offset)
            val remaining = (original - ageSeconds).coerceAtLeast(1L)
            writeInt(copy, offset, remaining)
        }
        return copy
    }

    /** Stores [response] if it's safe and useful to cache. */
    fun put(query: ByteArray, response: ByteArray) {
        val key = keyFor(query) ?: return
        val parsed = parseResponse(response) ?: return
        val ttl = minOf(parsed.minAnswerTtl, MAX_TTL_SECONDS)
        if (ttl < MIN_TTL_SECONDS) return
        val entry = Entry(response.copyOf(), System.currentTimeMillis(), ttl, parsed.ttlOffsets, parsed.questionEnd)
        synchronized(map) { map[key] = entry }
    }

    // ---- query -> cache key ------------------------------------------------

    /**
     * Key = lower-cased question (name + type + class) + the EDNS flags
     * (so e.g. a DNSSEC-requesting query never gets a non-DNSSEC answer).
     * Returns null for anything that isn't a plain single-question query.
     */
    private fun keyFor(query: ByteArray): String? {
        if (query.size < 17) return null
        val flags = u16(query, 2)
        if (flags and 0x8000 != 0) return null            // not a query
        if ((flags shr 11) and 0xF != 0) return null      // not a standard query
        if (u16(query, 4) != 1) return null               // exactly one question
        if (u16(query, 6) != 0 || u16(query, 8) != 0) return null // no answer/authority records

        val nameEnd = skipName(query, 12) ?: return null
        val questionEnd = nameEnd + 4
        if (questionEnd > query.size) return null

        val sb = StringBuilder(questionEnd + 8)
        for (i in 12 until questionEnd) {
            var b = query[i].toInt() and 0xFF
            if (b in 'A'.code..'Z'.code) b += 32 // lower-case (length bytes are < 64, never affected)
            sb.append(b.toChar())
        }

        var pos = questionEnd
        val additional = u16(query, 10)
        for (i in 0 until additional) {
            val recordNameEnd = skipName(query, pos) ?: return null
            if (recordNameEnd + 10 > query.size) return null
            val type = u16(query, recordNameEnd)
            if (type != TYPE_OPT) return null // only EDNS "OPT" extras are understood
            // For OPT, the 4 "TTL" bytes carry the extended flags (DO bit etc.).
            sb.append('|').append(readInt(query, recordNameEnd + 4))
            val rdLength = u16(query, recordNameEnd + 8)
            pos = recordNameEnd + 10 + rdLength
            if (pos > query.size) return null
        }
        return sb.toString()
    }

    // ---- response parsing --------------------------------------------------

    private class Parsed(val minAnswerTtl: Long, val ttlOffsets: IntArray, val questionEnd: Int)

    private fun parseResponse(r: ByteArray): Parsed? {
        if (r.size < 12) return null
        val flags = u16(r, 2)
        if (flags and 0x8000 == 0) return null      // must be a response
        if (flags and 0x0200 != 0) return null      // truncated
        if (flags and 0x000F != 0) return null      // only NOERROR
        val qd = u16(r, 4)
        val an = u16(r, 6)
        val ns = u16(r, 8)
        val ar = u16(r, 10)
        if (qd != 1 || an == 0) return null

        var pos = 12
        val nameEnd = skipName(r, pos) ?: return null
        pos = nameEnd + 4
        if (pos > r.size) return null
        val questionEnd = pos

        var minAnswerTtl = Long.MAX_VALUE
        val offsets = ArrayList<Int>()
        val total = an + ns + ar
        for (i in 0 until total) {
            val recNameEnd = skipName(r, pos) ?: return null
            if (recNameEnd + 10 > r.size) return null
            val type = u16(r, recNameEnd)
            val ttlOffset = recNameEnd + 4
            val rdLength = u16(r, recNameEnd + 8)
            if (type != TYPE_OPT) {
                offsets.add(ttlOffset)
                if (i < an) minAnswerTtl = minOf(minAnswerTtl, readInt(r, ttlOffset))
            }
            pos = recNameEnd + 10 + rdLength
            if (pos > r.size) return null
        }
        if (minAnswerTtl == Long.MAX_VALUE) return null
        return Parsed(minAnswerTtl, offsets.toIntArray(), questionEnd)
    }

    // ---- tiny DNS helpers --------------------------------------------------

    /** Returns the offset just past the (possibly compressed) name starting at [start], or null if malformed. */
    private fun skipName(b: ByteArray, start: Int): Int? {
        var pos = start
        while (true) {
            if (pos >= b.size) return null
            val len = b[pos].toInt() and 0xFF
            when {
                len == 0 -> return pos + 1
                len and 0xC0 == 0xC0 -> return if (pos + 2 <= b.size) pos + 2 else null
                len and 0xC0 != 0 -> return null
                else -> pos += len + 1
            }
        }
    }

    private fun u16(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)

    private fun readInt(b: ByteArray, o: Int): Long =
        ((b[o].toLong() and 0xFF) shl 24) or ((b[o + 1].toLong() and 0xFF) shl 16) or
            ((b[o + 2].toLong() and 0xFF) shl 8) or (b[o + 3].toLong() and 0xFF)

    private fun writeInt(b: ByteArray, o: Int, v: Long) {
        b[o] = ((v shr 24) and 0xFF).toByte()
        b[o + 1] = ((v shr 16) and 0xFF).toByte()
        b[o + 2] = ((v shr 8) and 0xFF).toByte()
        b[o + 3] = (v and 0xFF).toByte()
    }
}
