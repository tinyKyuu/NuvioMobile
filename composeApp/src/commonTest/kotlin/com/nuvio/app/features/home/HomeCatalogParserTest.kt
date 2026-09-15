package com.nuvio.app.features.home

import kotlin.test.Test
import kotlin.test.assertEquals

class HomeCatalogParserTest {

    @Test
    fun `parse catalog response de-duplicates repeated metas but preserves raw count`() {
        val result = HomeCatalogParser.parseCatalogResponse(
            """
            {
              "metas": [
                { "id": "mal:62516", "type": "series", "name": "A" },
                { "id": "mal:62516", "type": "series", "name": "A duplicate" },
                { "id": "mal:1", "type": "movie", "name": "B" }
              ]
            }
            """.trimIndent(),
        )

        assertEquals(3, result.rawItemCount)
        assertEquals(
            listOf("series:mal:62516", "movie:mal:1"),
            result.items.map { it.stableKey() },
        )
        assertEquals("A", result.items.first().name)
    }

    @Test
    fun `parse catalog response respects max item cap without losing raw count`() {
        val result = HomeCatalogParser.parseCatalogResponse(
            payload = """
                {
                  "metas": [
                    { "id": "tt1", "type": "movie", "name": "One" },
                    { "id": "tt1", "type": "movie", "name": "One duplicate" },
                    { "id": "tt2", "type": "movie", "name": "Two" },
                    { "id": "tt3", "type": "movie", "name": "Three" }
                  ]
                }
            """.trimIndent(),
            maxItems = 2,
        )

        assertEquals(4, result.rawItemCount)
        assertEquals(
            listOf("movie:tt1", "movie:tt2"),
            result.items.map { it.stableKey() },
        )
    }

    @Test
    fun `parse catalog response skips invalid metas before reaching max item cap`() {
        val result = HomeCatalogParser.parseCatalogResponse(
            payload = """
                {
                  "metas": [
                    "not-an-object",
                    { "type": "movie", "name": "Missing id" },
                    { "id": "", "type": "movie", "name": "Blank id" },
                    { "id": "tt0", "type": "", "name": "Blank type" },
                    { "id": "tt0", "type": "movie", "name": "" },
                    { "id": "tt1", "type": "movie", "name": "One" },
                    { "id": "tt2", "type": "series", "name": "Two" },
                    { "id": "tt3", "type": "movie", "name": "Three" }
                  ]
                }
            """.trimIndent(),
            maxItems = 2,
        )

        assertEquals(8, result.rawItemCount)
        assertEquals(
            listOf("movie:tt1", "series:tt2"),
            result.items.map { it.stableKey() },
        )
    }

    @Test
    fun `parse catalog response keeps raw released date for unreleased filtering`() {
        val result = HomeCatalogParser.parseCatalogResponse(
            payload = """
                {
                  "metas": [
                    {
                      "id": "tt1",
                      "type": "movie",
                      "name": "Future Movie",
                      "releaseInfo": "2027",
                      "released": "2027-05-12T00:00:00.000Z"
                    }
                  ]
                }
            """.trimIndent(),
        )

        assertEquals("2027", result.items.single().releaseInfo)
        assertEquals("2027-05-12T00:00:00.000Z", result.items.single().rawReleaseDate)
    }
}
