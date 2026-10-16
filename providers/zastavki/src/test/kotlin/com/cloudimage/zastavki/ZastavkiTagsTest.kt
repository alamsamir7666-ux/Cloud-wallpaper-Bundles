package com.cloudimage.zastavki

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The baked tag catalogue's matching semantics — the search surface's
 * offline half. Every query shape the provider can receive, scored the
 * way [ZastavkiTags] scores it: exact, prefix, contains, query-head and
 * per-token overlap, ties broken alphabetically, misses answered with
 * null and empty lists.
 */
class ZastavkiTagsTest {
    @Test
    fun theCatalogueIsTheLiveTagIndex() {
        // 1,809 English slugs harvested from the site's own /tag-list/
        // page (October 2026), letter-less junk dropped after a live
        // check showed every such slug serves a 1-2 furniture-link page.
        assertEquals(1809, ZastavkiTags.slugs.size)
        assertTrue(ZastavkiTags.slugs.contains("Night"))
        assertTrue(ZastavkiTags.slugs.contains("the_simpsons"))
        assertTrue(ZastavkiTags.slugs.contains("Iron_man"))
        // The junk the live check killed.
        assertFalse(ZastavkiTags.slugs.contains("1940"))
        assertFalse(ZastavkiTags.slugs.contains("_-105"))
    }

    @Test
    fun anExactQueryWinsOutright() {
        assertEquals("Night", ZastavkiTags.bestMatch("night"))
        assertEquals("Night", ZastavkiTags.bestMatch("  NIGHT  "))
    }

    @Test
    fun aContainsQueryBeatsEveryOtherContains() {
        // "simpsons" is contained in "the_simpsons" — no exact or
        // prefix tier fires, and the contains tier carries it.
        assertEquals("the_simpsons", ZastavkiTags.bestMatch("simpsons"))
    }

    @Test
    fun aMultiWordQueryMatchesItsUnderscoredSlug() {
        assertEquals("Iron_man", ZastavkiTags.bestMatch("iron man"))
        assertEquals("Game_of_Thrones", ZastavkiTags.bestMatch("game of thrones"))
    }

    @Test
    fun aQueryHeadMatchesItsLeadingWord() {
        // "sky wallpaper" starts with the slug "sky" — the leading-word
        // tier, above any token-overlap partial credit.
        assertEquals("Sky", ZastavkiTags.bestMatch("sky wallpaper"))
    }

    @Test
    fun aPrefixQueryAutocompletes() {
        val matches = ZastavkiTags.topMatches("night", 8)
        // The exact match leads, prefix-tier follow.
        assertEquals("Night", matches[0])
        assertTrue(matches.contains("Night_city"))
        assertTrue(matches.contains("Night_street"))
    }

    @Test
    fun tokenOverlapRescuesScatteredWords() {
        // Neither word alone names the slug, both together do.
        assertEquals("Night_city", ZastavkiTags.bestMatch("city night"))
    }

    @Test
    fun aMissIsANullNeverAStretch() {
        assertNull(ZastavkiTags.bestMatch("zzzqqq"))
        assertNull(ZastavkiTags.bestMatch("   "))
        assertTrue(ZastavkiTags.topMatches("zzzqqq", 8).isEmpty())
    }

    @Test
    fun topMatchesIsCappedAndBestFirst() {
        val matches = ZastavkiTags.topMatches("night", 3)
        assertEquals(3, matches.size)
        assertEquals("Night", matches.first())
    }

    @Test
    fun labelsAreDisplayNames() {
        assertEquals("The Simpsons", ZastavkiTags.labelOf("the_simpsons"))
        assertEquals("Iron Man", ZastavkiTags.labelOf("Iron_man"))
        // A word that already carries case keeps it.
        assertEquals("3D", ZastavkiTags.labelOf("3D"))
        // The parenthetical qualifier drops.
        assertEquals("Hulk", ZastavkiTags.labelOf("Hulk(comic_character)"))
        // Leading underscores are the tag list's own noise.
        assertEquals("Tree Stump", ZastavkiTags.labelOf("_tree_stump"))
    }
}
