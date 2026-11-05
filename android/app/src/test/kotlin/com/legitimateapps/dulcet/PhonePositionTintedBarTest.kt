package com.legitimateapps.dulcet

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.junit4.createComposeRule
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The bar's tint says whether anything has gone under it, however it got there: a programmatic
 * scroll, a restored position or a list that consumed no gesture feed no nested scroll.
 */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-port")
class PhonePositionTintedBarTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var list: LazyListState
    private lateinit var scope: CoroutineScope
    private lateinit var tinted: TopAppBarScrollBehavior
    private lateinit var stock: TopAppBarScrollBehavior

    private fun page(start: Int = 0) = compose.setContent {
        list = rememberLazyListState(start)
        scope = rememberCoroutineScope()
        tinted = rememberPositionTintedBar { list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 0 }
        stock = TopAppBarDefaults.pinnedScrollBehavior()
        Column {
            TopAppBar(title = { Text("Tinted") }, scrollBehavior = tinted)
            TopAppBar(title = { Text("Stock") }, scrollBehavior = stock)
            LazyColumn(state = list) { items(60) { Text("Row $it") } }
        }
    }

    private fun scrollTo(index: Int) {
        compose.runOnIdle { scope.launch { list.scrollToItem(index) } }
        compose.waitForIdle()
    }

    @Test fun aProgrammaticScrollTintsAndReturningClearsIt() {
        page()
        compose.waitForIdle()
        assertEquals(0f, tinted.state.overlappedFraction, "at rest at the top")
        scrollTo(10)
        assertTrue(tinted.state.overlappedFraction > 0.01f, "scrolled: ${tinted.state.overlappedFraction}")
        assertEquals(0f, stock.state.overlappedFraction, "control: the stock behaviour hears no programmatic scroll")
        scrollTo(0)
        assertEquals(0f, tinted.state.overlappedFraction, "back at the top")
    }

    @Test fun aRestoredPositionIsTintedFromItsFirstFrame() {
        page(start = 10)
        compose.waitForIdle()
        assertTrue(tinted.state.overlappedFraction > 0.01f, "restored: ${tinted.state.overlappedFraction}")
    }
}
