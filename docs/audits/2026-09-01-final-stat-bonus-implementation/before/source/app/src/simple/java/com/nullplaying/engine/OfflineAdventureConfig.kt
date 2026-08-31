package com.nullplaying.engine

/** Whole minutes in the console; defaults preserve the original 12h / 12min balance. */
data class OfflineAdventureConfig(
    val capacityMinutes: Long = DEFAULT_CAPACITY_MINUTES,
    val chargeMinutes: Long = DEFAULT_CHARGE_MINUTES,
) {
    init {
        require(capacityMinutes in 1L..MAX_CAPACITY_MINUTES)
        require(chargeMinutes in 1L..MAX_CHARGE_MINUTES)
    }

    val capacityMillis: Long get() = capacityMinutes * 60_000L

    companion object {
        const val CAPACITY_KEY = "offline_adventure_capacity_minutes"
        const val CHARGE_KEY = "offline_adventure_charge_minutes"
        const val DEFAULT_CAPACITY_MINUTES = 720L
        const val DEFAULT_CHARGE_MINUTES = 12L
        const val MAX_CAPACITY_MINUTES = 4_320L
        const val MAX_CHARGE_MINUTES = 1_440L

        /** Reject a malformed pair as a whole, instead of silently changing the balance. */
        fun parse(capacity: String, charge: String): OfflineAdventureConfig? {
            val capacityMinutes = capacity.trim().toLongOrNull() ?: return null
            val chargeMinutes = charge.trim().toLongOrNull() ?: return null
            if (capacityMinutes !in 1L..MAX_CAPACITY_MINUTES ||
                chargeMinutes !in 1L..MAX_CHARGE_MINUTES
            ) return null
            return OfflineAdventureConfig(capacityMinutes, chargeMinutes)
        }
    }
}
