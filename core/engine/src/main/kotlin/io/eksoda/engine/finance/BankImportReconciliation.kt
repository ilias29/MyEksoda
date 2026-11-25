package io.eksoda.engine.finance

/** An absent bank ID is deliberately not treated as proof that two equal payments are identical. */
data class ImportReconciliation(
    val newMovements: List<BankMovement>,
    val alreadyImported: List<BankMovement>,
    val conflicts: List<BankMovement>,
) {
    val canCommit: Boolean get() = conflicts.isEmpty()
}

object BankImportReconciliation {
    fun reconcile(existing: Iterable<BankMovement>, incoming: Iterable<BankMovement>): ImportReconciliation {
        val byId = mutableMapOf<Pair<String, String>, BankMovement>()
        existing.forEach { movement ->
            movement.sourceId?.let { id -> byId[movement.accountId to id] = movement }
        }
        val fresh = mutableListOf<BankMovement>()
        val duplicates = mutableListOf<BankMovement>()
        val conflicts = mutableListOf<BankMovement>()
        incoming.forEach { movement ->
            val id = movement.sourceId
            if (id == null) {
                fresh.add(movement)
            } else {
                val key = movement.accountId to id
                val previous = byId[key]
                when {
                    previous == null -> {
                        fresh.add(movement)
                        byId[key] = movement
                    }
                    previous.date == movement.date &&
                        previous.description == movement.description &&
                        previous.amountMinor == movement.amountMinor -> duplicates.add(movement)
                    else -> conflicts.add(movement)
                }
            }
        }
        return ImportReconciliation(fresh, duplicates, conflicts)
    }
}
