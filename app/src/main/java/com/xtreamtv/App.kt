package com.xtreamtv

import android.app.Application
import android.content.Context
import com.xtreamtv.data.FavoritesStore
import com.xtreamtv.data.SessionManager
import com.xtreamtv.data.XtreamApiClient

class App : Application() {

    lateinit var session: SessionManager
        private set
    lateinit var favorites: FavoritesStore
        private set
    lateinit var api: XtreamApiClient
        private set

    override fun onCreate() {
        super.onCreate()
        session = SessionManager(this)
        favorites = FavoritesStore(this)
        api = XtreamApiClient(session)
    }

    companion object {
        fun from(context: Context): App = context.applicationContext as App
    }
}
