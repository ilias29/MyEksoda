package io.eksoda.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "finance_subscription")
data class SubscriptionRow(
    @PrimaryKey val key: String,
    val name: String,
    val expectedMonthlyMinor: Long,
)
