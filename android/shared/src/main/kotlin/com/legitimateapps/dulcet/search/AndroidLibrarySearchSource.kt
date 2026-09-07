package com.legitimateapps.dulcet.search

import android.content.Context
import com.legitimateapps.dulcet.core.AndroidLibraryDatabase
import com.legitimateapps.dulcet.core.SearchResultItem

public class AndroidLibrarySearchSource(context: Context, private val providerId: String) : LocalSearchSource {
    private val library = AndroidLibraryDatabase(context)
    override suspend fun search(query: String): List<SearchResultItem> = library.search(providerId, query)
}
