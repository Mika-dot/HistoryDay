package com.example.dayflash.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ClipDao {
    @Insert
    suspend fun insert(clip: ClipEntity)

    @Query("SELECT * FROM clips WHERE dayKey = :day ORDER BY capturedAt")
    suspend fun clipsForDay(day: String): List<ClipEntity>

    @Query(
        """
        SELECT dayKey,
               COUNT(*) AS count,
               MIN(path) AS previewPath,
               MAX(capturedAt) AS latestCapturedAt
        FROM clips
        GROUP BY dayKey
        ORDER BY dayKey DESC
        """
    )
    fun observeDays(): Flow<List<DaySummary>>
}

data class DaySummary(
    val dayKey: String,
    val count: Int,
    val previewPath: String?,
    val latestCapturedAt: Long,
)
