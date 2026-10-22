package co.com.pragma.transfers.model.transfer;

/** Estados de una transferencia (diagrama de estados de la charla 1). */
public enum TransferStatus {
    RECEIVED,
    DEBITED,
    COMPLETED,
    REJECTED,
    PENDING_REVIEW,
    COMPENSATED;

    /** Estados finales: una repetición idempotente devuelve la transferencia tal cual. */
    public boolean isFinal() {
        return this == COMPLETED || this == REJECTED || this == PENDING_REVIEW || this == COMPENSATED;
    }

    /**
     * RN-05: suman al acumulado diario las transferencias que movieron o pueden
     * mover dinero. REJECTED y COMPENSATED no cuentan.
     */
    public boolean countsTowardDailyLimit() {
        return this != REJECTED && this != COMPENSATED;
    }
}
