package com.legitimateapps.dulcet

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertTrue

class AndroidStringGlossaryTest {
    @Test fun defaultStringValuesUseUsEnglish() {
        val britishSpelling = Regex("favour|colour", RegexOption.IGNORE_CASE)
        val stringTypes = setOf("string", "plurals", "string-array")
        val parser = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        val violations = mutableListOf<String>()

        for (module in listOf("shared", "app", "tv")) {
            val sources = File("../$module/src")
            check(sources.isDirectory) { "Missing Android source directory: $sources" }
            for (sourceSet in sources.listFiles().orEmpty()) {
                val values = sourceSet.resolve("res/values")
                for (file in values.listFiles { entry -> entry.extension == "xml" }.orEmpty()) {
                    val resources = parser.parse(file).documentElement.childNodes
                    for (index in 0 until resources.length) {
                        val resource = resources.item(index)
                        if (resource.nodeName !in stringTypes) continue
                        if (britishSpelling.containsMatchIn(resource.textContent)) {
                            val name = resource.attributes.getNamedItem("name").nodeValue
                            violations += "$file: $name"
                        }
                    }
                }
            }
        }

        assertTrue(violations.isEmpty(), "Use US English in these Android resources:\n${violations.joinToString("\n")}")
    }
}
