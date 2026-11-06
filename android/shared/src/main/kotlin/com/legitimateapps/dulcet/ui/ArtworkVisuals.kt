package com.legitimateapps.dulcet.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp

/** Entity-specific glyphs shared by the phone and TV artwork frames. */
public enum class ArtworkEntity(public val placeholder: androidx.compose.ui.graphics.vector.ImageVector) {
    Album(DulcetIcons.Album),
    Artist(DulcetIcons.Person),
    Playlist(DulcetIcons.PlaylistPlay),
    Song(DulcetIcons.MusicNote),
}

/**
 * A real cover fills its frame. Missing artwork uses the same frame shape and a centered,
 * entity-specific glyph. Callers provide platform theme colors so TV can use its own scheme.
 */
@Composable
public fun MediaArtwork(
    image: ImageBitmap?,
    entity: ArtworkEntity,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(12.dp),
    placeholderColor: Color,
    iconColor: Color,
) {
    BoxWithConstraints(modifier.clip(shape).background(placeholderColor), contentAlignment = Alignment.Center) {
        if (image != null) {
            Image(image, contentDescription, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            val glyphSize = minOf(maxWidth, maxHeight) * 0.38f
            Image(
                imageVector = entity.placeholder,
                contentDescription = contentDescription,
                modifier = Modifier.align(Alignment.Center).size(glyphSize),
                colorFilter = ColorFilter.tint(iconColor),
            )
        }
    }
}
