package io.eksoda.ui

import io.eksoda.core.database.dao.MonthlyMovementRow
import io.eksoda.core.database.entity.MonthlyPlanRow
import io.eksoda.core.database.entity.NetWorthRow
import io.eksoda.core.database.entity.SubscriptionRow

internal data class AdvisorInputs(
    val rows: List<MonthlyMovementRow>,
    val plan: MonthlyPlanRow?,
    val subscriptions: List<SubscriptionRow>,
    val worth: List<NetWorthRow>,
)
