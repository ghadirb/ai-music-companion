package com.ghadirb.aimusic.music

import org.junit.Assert.assertEquals
import org.junit.Test

class CollectionCopiesTest {
    private fun col(url: String, source: String, iso: String, name: String = "Artoosh - Goldenhits.zip", collection: String = "Goldenhits") = OnlineCollection(
        url = url, sourceId = source, source = source, fileName = name, collection = collection, artist = "Artoosh",
        artistKey = Autoindex.artistKey("Artoosh"), size = 10, folderIso = iso, entryCount = null,
    )

    @Test fun copiesOfOneCollectionBecomeOneRowWithTheOtherLinksKept() {
        val r = CollectionCopies.group(listOf(col("https://g/a.zip", "giso", "2026-08-01"), col("https://h/a.zip", "ahaang", "2026-09-01")))
        assertEquals(1, r.size)
        assertEquals("https://h/a.zip", r[0].url)            // higher-priority source is the main link
        assertEquals(listOf("https://g/a.zip"), r[0].altUrls)  // the other copy is the fallback
        assertEquals("2026-09-01", r[0].folderIso)
    }

    @Test fun differentCollectionsStayApartAndAreNewestFirst() {
        val r = CollectionCopies.group(listOf(
            col("https://h/old.zip", "ahaang", "2025-01-01", "Artoosh - Best.zip", "Best"),
            col("https://h/new.zip", "ahaang", "2026-01-01", "Artoosh - Goldenhits.zip", "Goldenhits"),
        ))
        assertEquals(listOf("https://h/new.zip", "https://h/old.zip"), r.map { it.url })
    }

    @Test fun sameLinkTwiceIsNotItsOwnAlternative() {
        val r = CollectionCopies.group(listOf(col("https://h/a.zip", "ahaang", "2026-09-01"), col("https://h/a.zip", "ahaang", "2026-09-01")))
        assertEquals(1, r.size); assertEquals(emptyList<String>(), r[0].altUrls)
    }

    @Test fun serverPrioritiesDecideWhichCopyIsMain() {
        val r = CollectionCopies.group(
            listOf(col("https://g/a.zip", "giso", "2026-08-01"), col("https://h/a.zip", "ahaang", "2026-09-01")),
            priority = mapOf("giso" to 1, "ahaang" to 2),
        )
        assertEquals("https://g/a.zip", r[0].url)
    }
}
