package com.sheltron.captioner

import android.app.Application
import com.sheltron.captioner.data.Repository
import com.sheltron.captioner.data.db.AppDatabase

class CaptionerApp : Application() {
    lateinit var repository: Repository
        private set

    override fun onCreate() {
        super.onCreate()
        val db = AppDatabase.get(this)
        repository = Repository(db.sessionDao(), db.lineDao())
    }
}
