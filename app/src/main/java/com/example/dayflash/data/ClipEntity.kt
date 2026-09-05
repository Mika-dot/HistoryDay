package com.example.dayflash.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "clips")
data class ClipEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val path: String,
    val capturedAt: Long,
    val dayKey: String,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val placeName: String? = null,
    val osmType: String? = null,
    val osmId: Long? = null,
)
