package nl.jessedezwart.piglinpanel.mods

/**
 * Whether an installed version satisfies the range a dependent declares.
 *
 * Mod loaders write two different range syntaxes. Fabric and Quilt use npm-flavoured predicates
 * (`>=0.92.0`, `~1.2`, `^3`, `1.2.x`, `*`), joined by whitespace for "and" and by `||` for "or".
 * Forge and NeoForge use Maven ranges (`[1.0,2.0)`, `[1.0,)`, `[1.0]`), where a comma between
 * brackets means "or". Both are accepted here, told apart by their first character.
 *
 * Every uncertainty resolves to satisfied: an unparseable range, a version string neither side can
 * compare, a missing version. This exists to warn about a broken dependency chain, and a warning
 * that fires on a syntax it does not know would be worse than the silence it replaces.
 */
object ModVersionRange {
    /** Whether [version] falls inside [range]. True whenever either is absent or unreadable. */
    fun satisfies(version: String?, range: String?): Boolean {
        val wanted = range?.trim()?.takeIf { it.isNotEmpty() } ?: return true
        val actual = version?.trim()?.takeIf { it.isNotEmpty() } ?: return true
        if (wanted == "*" || wanted.equals("any", ignoreCase = true)) return true
        if (!isComparable(actual)) return true
        return try {
            if (wanted.startsWith("[") || wanted.startsWith("(")) {
                matchesMaven(actual, wanted)
            } else {
                matchesPredicates(actual, wanted)
            }
        } catch (e: Exception) {
            true
        }
    }

    /**
     * Orders two version strings: negative when [a] is older. Numeric segments compare as numbers,
     * so `0.100` sorts above `0.92` — the comparison every mod version hinges on, and the one a
     * plain string compare gets backwards.
     */
    fun compare(a: String, b: String): Int {
        val (coreA, preA) = split(a)
        val (coreB, preB) = split(b)
        compareSegments(coreA, coreB).let { if (it != 0) return it }
        return comparePreRelease(preA, preB)
    }

    /**
     * A version split into its release segments and its pre-release tag.
     *
     * Build metadata is dropped: Fabric mods carry the Minecraft version there (`4.2.6+1.21.1`),
     * and it says nothing about which of two builds is newer.
     */
    private fun split(version: String): Pair<List<String>, List<String>> {
        val withoutBuild = version.substringBefore('+').trim().removePrefix("v")
        val core = withoutBuild.substringBefore('-')
        val pre = withoutBuild.substringAfter('-', "")
        return core.split('.').filter { it.isNotEmpty() } to pre.split('.', '-').filter { it.isNotEmpty() }
    }

    /** Compares release segments, treating a missing segment as zero so `1.2` equals `1.2.0`. */
    private fun compareSegments(a: List<String>, b: List<String>): Int {
        for (i in 0 until maxOf(a.size, b.size)) {
            val result = compareSegment(a.getOrElse(i) { "0" }, b.getOrElse(i) { "0" })
            if (result != 0) return result
        }
        return 0
    }

    private fun compareSegment(a: String, b: String): Int {
        val numberA = a.toLongOrNull()
        val numberB = b.toLongOrNull()
        return when {
            numberA != null && numberB != null -> numberA.compareTo(numberB)
            // A segment that is not a number sorts below one that is, following semver's
            // pre-release ordering: `1.0.0-rc` is older than `1.0.0`, and `rc` is no release number.
            numberA != null -> 1
            numberB != null -> -1
            else -> a.compareTo(b)
        }
    }

    /** Semver's rule: a release outranks any pre-release of the same version. */
    private fun comparePreRelease(a: List<String>, b: List<String>): Int = when {
        a.isEmpty() && b.isEmpty() -> 0
        a.isEmpty() -> 1
        b.isEmpty() -> -1
        else -> compareSegments(a, b)
    }

    /** `>=1.0 <2.0 || >=3.0`: alternatives separated by `||`, each a conjunction of predicates. */
    private fun matchesPredicates(version: String, range: String): Boolean =
        range.split("||").any { alternative ->
            val predicates = alternative.split(' ', ',', '\t')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            predicates.isEmpty() || predicates.all { matchesPredicate(version, it) }
        }

    /**
     * Whether a string can be ordered against another: its first segment is a number. Anything
     * else — a codename, a syntax this does not know — is not a version it can reason about, and
     * comparing it would turn an unreadable range into a confident failure.
     */
    private fun isComparable(value: String): Boolean =
        split(value).first.firstOrNull()?.toLongOrNull() != null

    private fun matchesPredicate(version: String, predicate: String): Boolean {
        if (predicate == "*" || predicate.equals("any", ignoreCase = true)) return true
        val operator = OPERATORS.firstOrNull { predicate.startsWith(it) }
        val operand = predicate.removePrefix(operator ?: "").trim()
        if (operand.isEmpty()) return true
        // A wildcard operand is a prefix, not a version, so it is checked before this rejects it.
        if (!isComparable(operand) && operator != null) return true
        return when (operator) {
            ">=" -> compare(version, operand) >= 0
            "<=" -> compare(version, operand) <= 0
            ">" -> compare(version, operand) > 0
            "<" -> compare(version, operand) < 0
            "!=" -> compare(version, operand) != 0
            // `~1.2.3` allows patch bumps, `^1.2.3` allows minor bumps. Both are a half-open
            // interval, so they are expressed as one rather than special-cased twice.
            "~" -> withinPrefix(version, operand, keep = 2)
            "^" -> withinPrefix(version, operand, keep = 1)
            else -> matchesExact(version, operand)
        }
    }

    /** Longest first, so `>=` is not read as `>` with a stray `=`. */
    private val OPERATORS = listOf(">=", "<=", "!=", "==", ">", "<", "=", "~", "^")

    /**
     * At or above [floor], and below the next bump of its first [keep] segments: `~1.2.3` accepts
     * `1.2.9` but not `1.3.0`.
     */
    private fun withinPrefix(version: String, floor: String, keep: Int): Boolean {
        if (compare(version, floor) < 0) return false
        val (core, _) = split(floor)
        // Too few segments to build a ceiling from: `~1` says nothing about where 1.x ends.
        if (core.size < keep) return true
        val ceiling = core.take(keep).toMutableList()
        val bumped = ceiling.removeAt(ceiling.lastIndex).toLongOrNull()?.plus(1) ?: return true
        ceiling += bumped.toString()
        return compare(version, ceiling.joinToString(".")) < 0
    }

    /** An exact predicate, with `1.2.x` and `1.2.*` read as "any patch of 1.2". */
    private fun matchesExact(version: String, operand: String): Boolean {
        val wildcard = operand.indexOfFirst { it == 'x' || it == 'X' || it == '*' }
        if (wildcard < 0) return if (isComparable(operand)) compare(version, operand) == 0 else true
        val prefix = operand.take(wildcard).trim('.', '-')
        if (prefix.isEmpty()) return true
        return withinPrefix(version, prefix, keep = prefix.split('.').size)
    }

    /** `[1.0,2.0),[3.0,)`: bracketed intervals, any one of which may match. */
    private fun matchesMaven(version: String, range: String): Boolean =
        splitMavenIntervals(range).any { matchesMavenInterval(version, it) }

    /** Splits on the commas *between* intervals, leaving the one inside each interval alone. */
    private fun splitMavenIntervals(range: String): List<String> {
        val intervals = mutableListOf<String>()
        val current = StringBuilder()
        var depth = 0
        for (character in range) {
            when (character) {
                '[', '(' -> {
                    depth++
                    current.append(character)
                }
                ']', ')' -> {
                    depth--
                    current.append(character)
                }
                ',' -> if (depth == 0) {
                    intervals += current.toString()
                    current.clear()
                } else {
                    current.append(character)
                }
                else -> current.append(character)
            }
        }
        intervals += current.toString()
        return intervals.map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun matchesMavenInterval(version: String, interval: String): Boolean {
        // A bare version outside brackets is Maven's "recommended, but anything goes" form, which
        // the loaders do not enforce either.
        if (!interval.startsWith("[") && !interval.startsWith("(")) return true
        if (!interval.endsWith("]") && !interval.endsWith(")")) return true
        val lowerInclusive = interval.startsWith("[")
        val upperInclusive = interval.endsWith("]")
        val body = interval.substring(1, interval.length - 1)
        if (!body.contains(',')) {
            // `[1.0]` pins one version exactly.
            val pinned = body.trim()
            return pinned.isEmpty() || !isComparable(pinned) || compare(version, pinned) == 0
        }
        val lower = body.substringBefore(',').trim().takeIf(::isComparable).orEmpty()
        val upper = body.substringAfter(',').trim().takeIf(::isComparable).orEmpty()
        if (lower.isNotEmpty()) {
            val result = compare(version, lower)
            if (if (lowerInclusive) result < 0 else result <= 0) return false
        }
        if (upper.isNotEmpty()) {
            val result = compare(version, upper)
            if (if (upperInclusive) result > 0 else result >= 0) return false
        }
        return true
    }
}
