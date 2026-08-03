package com.example.dayflash.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "clips")
data class ClipEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val path: String,
    val capturedAt: Long,
    val dayKey: String
)
