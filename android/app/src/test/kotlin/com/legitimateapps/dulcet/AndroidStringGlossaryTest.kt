package com.legitimateapps.dulcet

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertTrue

class AndroidStringGlossaryTest {
    @Test fun defaultStringValuesUseUsEnglish() {
        val britishSpelling = Regex("favour|colour", RegexOption.IGNORE_CASE)
        val resourceReference = Regex("""@(?:[\w.]+:)?string/\w+""")
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
                        val entries = when (resource.nodeName) {
                            "string" -> listOf(resource)
                            "plurals", "string-array" -> {
                                val children = resource.childNodes
                                (0 until children.length).map(children::item).filter { it.nodeName == "item" }
                            }
                            else -> continue
                        }
                        val usesBritishSpelling = entries.any { entry ->
                            val value = entry.textContent.trim()
                            !resourceReference.matches(value) && britishSpelling.containsMatchIn(value)
                        }
                        if (usesBritishSpelling) {
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
