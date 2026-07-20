package com.urlxl.mail.data

import android.content.Context
import androidx.room.Room
import com.urlxl.mail.SingletonGraph

class DataGraph(context: Context) {
    private val appContext = context.applicationContext
    val database: AppDatabase = Room.databaseBuilder(appContext, AppDatabase::class.java, "kypost_mail.db")
        .addMigrations(
            AppDatabase.MIGRATION_1_2,
            AppDatabase.MIGRATION_2_3,
            AppDatabase.MIGRATION_3_4,
            AppDatabase.MIGRATION_4_5,
            AppDatabase.MIGRATION_5_6,
        )
        .build()
}

/** Standalone singleton, kept independent of PushGraph/KyPostApp — mirrors how PushGraph itself
 *  stands alone rather than nesting inside another graph. */
object DataRuntime {
    private val holder = SingletonGraph(::DataGraph)

    fun graph(context: Context): DataGraph = holder.get(context)
}
