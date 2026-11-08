package com.legitimateapps.dulcet.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class ArtworkTileStateTest {
    private fun tile(outcome: ArtworkOutcome, painted: Boolean = false, serverDrawn: Boolean = false) =
        ArtworkTile("key", 256, outcome).also { it.painted = painted; it.serverDrawn = serverDrawn }

    @Test fun anAnsweredTileWithNoFrameYetIsUndrawnNotInNoBucket() {
        assertEquals(ArtworkTileState.Undrawn, tile(ArtworkOutcome.Answered).state())
        assertEquals(ArtworkTileState.Painted, tile(ArtworkOutcome.Answered, painted = true).state())
    }

    @Test fun theServersDrawnNoteIsAMissingCoverNotAnUndrawnOne() {
        assertEquals(ArtworkTileState.Missing, tile(ArtworkOutcome.Answered, serverDrawn = true).state())
    }

    @Test fun everyOutcomeHasOneState() {
        assertEquals(
            listOf(ArtworkTileState.Waiting, ArtworkTileState.Undrawn, ArtworkTileState.Missing, ArtworkTileState.Failed,
                ArtworkTileState.NotKept, ArtworkTileState.Keyless),
            ArtworkOutcome.entries.map { tile(it).state() },
        )
    }

    @Test fun aPaintedPictureOutranksWhatTheLastFetchSaid() {
        assertEquals(ArtworkTileState.Painted, tile(ArtworkOutcome.Failed, painted = true).state())
    }
}
