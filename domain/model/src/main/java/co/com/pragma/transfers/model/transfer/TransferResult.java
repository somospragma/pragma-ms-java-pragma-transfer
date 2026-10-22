package co.com.pragma.transfers.model.transfer;

/**
 * Resultado de crear una transferencia. {@code created} distingue el 201
 * (transferencia nueva) del 200 (repetición idempotente, RN-08).
 */
public record TransferResult(Transfer transfer, boolean created) {

    public static TransferResult created(Transfer transfer) {
        return new TransferResult(transfer, true);
    }

    public static TransferResult replayed(Transfer transfer) {
        return new TransferResult(transfer, false);
    }
}
