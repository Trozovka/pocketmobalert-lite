package com.trozovka.pocketmobalert.core.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "alert_log")
data class AlertLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val crewDeviceIdHex: String,
    val crewLabel: String,
    val timestampMillis: Long,
    val latitude: Double?,
    val longitude: Double?,
)
