package nl.jessedezwart.piglinpanel.mods

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModVersionRangeTest {
    @Test
    fun comparesNumericSegmentsAsNumbers() {
        assertTrue(ModVersionRange.compare("0.100.0", "0.92.2") > 0)
        assertTrue(ModVersionRange.compare("1.2", "1.2.0") == 0)
        assertTrue(ModVersionRange.compare("1.10", "1.9") > 0)
    }

    @Test
    fun ignoresBuildMetadataAndRanksPreReleasesBelowTheirRelease() {
        assertTrue(ModVersionRange.compare("4.2.6+1.21.1", "4.2.6") == 0)
        assertTrue(ModVersionRange.compare("1.0.0-beta.2", "1.0.0") < 0)
        assertTrue(ModVersionRange.compare("1.0.0-beta.2", "1.0.0-beta.10") < 0)
    }

    @Test
    fun readsFabricPredicates() {
        assertTrue(ModVersionRange.satisfies("0.110.0", ">=0.100.0"))
        assertFalse(ModVersionRange.satisfies("0.92.2", ">=0.100.0"))
        assertTrue(ModVersionRange.satisfies("1.2.9", "~1.2.3"))
        assertFalse(ModVersionRange.satisfies("1.3.0", "~1.2.3"))
        assertTrue(ModVersionRange.satisfies("1.9.0", "^1.2.3"))
        assertFalse(ModVersionRange.satisfies("2.0.0", "^1.2.3"))
        assertTrue(ModVersionRange.satisfies("1.2.7", "1.2.x"))
        assertFalse(ModVersionRange.satisfies("1.3.0", "1.2.x"))
    }

    @Test
    fun readsConjunctionsAndAlternatives() {
        assertTrue(ModVersionRange.satisfies("1.5.0", ">=1.0.0 <2.0.0"))
        assertFalse(ModVersionRange.satisfies("2.5.0", ">=1.0.0 <2.0.0"))
        assertTrue(ModVersionRange.satisfies("3.1.0", ">=1.0.0 <2.0.0 || >=3.0.0"))
    }

    @Test
    fun readsMavenRanges() {
        assertTrue(ModVersionRange.satisfies("1.5", "[1.0,2.0)"))
        assertFalse(ModVersionRange.satisfies("2.0", "[1.0,2.0)"))
        assertTrue(ModVersionRange.satisfies("2.0", "[1.0,2.0]"))
        assertFalse(ModVersionRange.satisfies("0.9", "[1.0,)"))
        assertTrue(ModVersionRange.satisfies("21.1.72", "[21.1.62,)"))
        assertTrue(ModVersionRange.satisfies("1.0", "[1.0]"))
        assertFalse(ModVersionRange.satisfies("1.1", "[1.0]"))
    }

    @Test
    fun readsAMavenUnionWithoutSplittingInsideAnInterval() {
        assertTrue(ModVersionRange.satisfies("1.5", "[1.0,2.0),[3.0,)"))
        assertTrue(ModVersionRange.satisfies("3.5", "[1.0,2.0),[3.0,)"))
        assertFalse(ModVersionRange.satisfies("2.5", "[1.0,2.0),[3.0,)"))
    }

    @Test
    fun treatsEveryUncertaintyAsSatisfied() {
        assertTrue(ModVersionRange.satisfies("1.0.0", null))
        assertTrue(ModVersionRange.satisfies(null, ">=2.0.0"))
        assertTrue(ModVersionRange.satisfies("1.0.0", "*"))
        assertTrue(ModVersionRange.satisfies("1.0.0", ""))
        // Nothing here understands this; silence beats a warning that cannot be acted on.
        assertTrue(ModVersionRange.satisfies("1.0.0", "{weird}"))
    }
}
