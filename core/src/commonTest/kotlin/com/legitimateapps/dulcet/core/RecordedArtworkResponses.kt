package com.legitimateapps.dulcet.core

/** Canonicalized bytes recorded from Navidrome 0.63.2's getCoverArt endpoint. */
internal object RecordedArtworkResponses {
    const val VALID_JPEG_BODY_BYTE_COUNT = 3_294

    const val VALID_JPEG_PREFIX_HEX =
        "ffd8ffdb008400080606070605080707070909080a0c140d0c0b0b0c1912130f" +
            "141d1a1f1e1d1a1c1c20242e2720222c231c1c2837292c30313434341f27393d"

    const val MISSING_XML =
        "<subsonic-response xmlns=\"http://subsonic.org/restapi\" status=\"failed\" " +
            "version=\"1.16.1\" type=\"navidrome\" serverVersion=\"0.63.2 (be10f89c)\" " +
            "openSubsonic=\"true\"><error code=\"70\" message=\"Artwork not found\">" +
            "</error></subsonic-response>"

    const val MISSING_JSON =
        "{\"subsonic-response\":{\"status\":\"failed\",\"version\":\"1.16.1\"," +
            "\"type\":\"navidrome\",\"serverVersion\":\"0.63.2 (be10f89c)\"," +
            "\"openSubsonic\":true,\"error\":{\"code\":70," +
            "\"message\":\"Artwork not found\"}}}"
}

internal fun String.recordedHexBytes(): ByteArray {
    require(length % 2 == 0)
    return ByteArray(length / 2) { index ->
        substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}
