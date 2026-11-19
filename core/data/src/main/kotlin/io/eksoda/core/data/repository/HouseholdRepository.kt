package io.eksoda.core.data.repository

import io.eksoda.core.model.Household
import kotlinx.coroutines.flow.Flow
import java.math.BigDecimal

/**
 * The one household in this database. There are no members, roles or invitations. Null until
 * onboarding creates it.
 */
interface HouseholdRepository {
    val household: Flow<Household?>

    suspend fun createHousehold(
        name: String,
        defaultCurrency: String,
        timezone: String,
    ): Result<Household>

    suspend fun updateHousehold(
        name: String,
        defaultCurrency: String,
        timezone: String,
    ): Result<Unit>

    /** The annual income the e-payment target is computed from; null clears it. */
    suspend fun setAnnualIncome(income: BigDecimal?): Result<Unit>
}
