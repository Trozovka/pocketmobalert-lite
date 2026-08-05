package com.trozovka.pocketmobalert.core.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface AlertLogDao {
    @Insert
    suspend fun insert(entry: AlertLogEntity): Long

    @Query("SELECT * FROM alert_log ORDER BY timestampMillis DESC")
    suspend fun getAll(): List<AlertLogEntity>

    @Query("SELECT * FROM alert_log ORDER BY timestampMillis DESC LIMIT 1")
    suspend fun getMostRecent(): AlertLogEntity?
}
