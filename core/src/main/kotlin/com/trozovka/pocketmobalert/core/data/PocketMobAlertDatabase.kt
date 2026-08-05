package com.trozovka.pocketmobalert.core.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [AlertLogEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class PocketMobAlertDatabase : RoomDatabase() {
    abstract fun alertLogDao(): AlertLogDao

    companion object {
        @Volatile
        private var instance: PocketMobAlertDatabase? = null

        fun getInstance(context: Context): PocketMobAlertDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    PocketMobAlertDatabase::class.java,
                    "pocketmobalert.db",
                )
                    // Pre-release schema churn only -- no real user data to preserve yet.
                    .fallbackToDestructiveMigration()
                    .build().also { instance = it }
            }
    }
}
