@file:OptIn(kotlinx.cinterop.BetaInteropApi::class)

package com.legitimateapps.dulcet.core

import platform.Foundation.NSString
import platform.Foundation.create
import platform.Foundation.decomposedStringWithCompatibilityMapping

internal actual fun compatibilityDecomposed(value: String): String =
    NSString.create(string = value).decomposedStringWithCompatibilityMapping
