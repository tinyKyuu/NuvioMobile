package com.nuvio.app.features.details

import kotlin.test.Test
import kotlin.test.assertEquals

class MetaDetailsCertificationTest {
    @Test
    fun explicitRatingSurvivesMalformedUnusedFallbacks() {
        assertRating("PG-13", """"ageRating":" PG-13 ","app_extras":{"certificationLocal":{},"certification":[]}""")
    }

    @Test
    fun localizedRatingSurvivesMalformedUnusedDefault() {
        assertRating("16", """"app_extras":{"certificationLocal":" 16 ","certification":{}}""")
    }

    @Test
    fun malformedExplicitAndLocalizedRatingsFallThrough() {
        for (value in listOf("{}", "[]", "true", "false", "null")) {
            assertRating("TV-MA", """"ageRating":$value,"app_extras":{"certificationLocal":$value,"certification":" TV-MA "}""")
        }
    }

    @Test
    fun malformedOptionalFieldsDoNotDiscardMetadata() {
        for (value in listOf("{}", "[]", "true", "false", "null")) {
            assertRating(null, """"ageRating":$value,"app_extras":{"certificationLocal":$value,"certification":$value}""")
        }
    }

    @Test
    fun booleanRatingsAreIgnored() {
        assertRating("PG", """"ageRating":true,"app_extras":{"certificationLocal":false,"certification":"PG"}""")
        assertRating(null, """"app_extras":{"certification":false}""")
    }

    @Test
    fun blankRatingsFallThroughInOrder() {
        assertRating("12", """"ageRating":" \t ","app_extras":{"certificationLocal":" 12 ","certification":"R"}""")
        assertRating("R", """"ageRating":" ","app_extras":{"certificationLocal":" ","certification":" R "}""")
        assertRating(null, """"ageRating":" ","app_extras":{"certificationLocal":"", "certification":" "}""")
    }

    @Test
    fun explicitRatingPrecedesValidFallbacks() {
        assertRating("PG", """"ageRating":" PG ","app_extras":{"certificationLocal":"16","certification":"R"}""")
    }

    @Test
    fun numericRatingsRemainCompatible() {
        assertRating("16", """"ageRating":16,"app_extras":{"certificationLocal":"PG"}""")
        assertRating("12", """"app_extras":{"certificationLocal":12,"certification":"PG"}""")
        assertRating("18", """"app_extras":{"certification":18}""")
    }

    @Test
    fun missingAndDefaultOnlyRatings() {
        assertRating(null, """"app_extras":{}""")
        assertRating(null, """"description":"No ratings"""")
        assertRating("PG", """"app_extras":{"certification":" PG "}""")
        for (value in listOf("{}", "[]", "true", "null")) {
            assertRating(null, """"app_extras":$value""")
        }
    }

    private fun assertRating(expected: String?, fields: String) {
        val result = MetaDetailsParser.parse(
            """{"meta":{"id":"show","type":"series","name":"Retained title",$fields,
                "videos":[{"id":"show:1:1","title":"Retained episode","season":1,"episode":1}]}}""",
        )
        assertEquals(expected, result.ageRating, fields)
        assertEquals("Retained title", result.name)
        assertEquals("show:1:1", result.videos.single().id)
        assertEquals("Retained episode", result.videos.single().title)
    }
}
