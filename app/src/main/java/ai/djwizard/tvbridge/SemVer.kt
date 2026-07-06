package ai.djwizard.tvbridge

// Strict MAJOR.MINOR.PATCH parser for the /bridge/update version contract.
//
// The relay's manifest promises plain "0.6.4"-style versions — no leading "v",
// no pre-release or build metadata, no leading zeros. Anything else is
// rejected (null) so the updater fails closed instead of mis-comparing:
// a malformed version must never be treated as "newer".
object SemVer {

    private val STRICT = Regex("""^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$""")

    // parse returns the three numeric components, or null when the string is
    // not strict MAJOR.MINOR.PATCH (including "-rc" tags, "v" prefixes, or
    // components too large for Int).
    fun parse(raw: String): List<Int>? {
        val m = STRICT.matchEntire(raw.trim()) ?: return null
        val parts = m.groupValues.drop(1).map { it.toIntOrNull() ?: return null }
        return parts
    }

    // isNewer reports whether remote > local. Returns null when either side is
    // malformed — callers must surface that as an error, never as "up to date"
    // silently and never as an update offer.
    fun isNewer(remote: String, local: String): Boolean? {
        val r = parse(remote) ?: return null
        val l = parse(local) ?: return null
        for (i in 0..2) {
            if (r[i] != l[i]) return r[i] > l[i]
        }
        return false
    }
}
