package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "domain_colors")
data class DomainColorEntity(
    @PrimaryKey
    val domain: String,
    val colorHex: String,
    val updatedAt: Long = System.currentTimeMillis()
)
