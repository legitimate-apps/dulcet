package com.legitimateapps.dulcet.core

internal expect fun compatibilityDecomposed(value: String): String

internal fun normalizeSearchText(value: String): String = compatibilityDecomposed(value)
    .lowercase()
    .filterNot { character ->
        character.category == CharCategory.NON_SPACING_MARK ||
            character.category == CharCategory.COMBINING_SPACING_MARK ||
            character.category == CharCategory.ENCLOSING_MARK
    }
