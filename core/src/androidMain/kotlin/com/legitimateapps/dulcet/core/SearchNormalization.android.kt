package com.legitimateapps.dulcet.core

import java.text.Normalizer

internal actual fun compatibilityDecomposed(value: String): String =
    Normalizer.normalize(value, Normalizer.Form.NFKD)
