package co.com.pragma.transfers.model.event;

import co.com.pragma.transfers.model.transfer.TransferStatus;
import java.util.Optional;

/** Tipos de evento de openapi/transfers-events.asyncapi.yaml. */
public enum TransferEventType {
    TransferCompleted,
    TransferRejected,
    TransferPendingReview,
    TransferCompensated;

    /** Evento que corresponde a un estado final; los intermedios no publican. */
    public static Optional<TransferEventType> forStatus(TransferStatus status) {
        return switch (status) {
            case COMPLETED -> Optional.of(TransferCompleted);
            case REJECTED -> Optional.of(TransferRejected);
            case PENDING_REVIEW -> Optional.of(TransferPendingReview);
            case COMPENSATED -> Optional.of(TransferCompensated);
            default -> Optional.empty();
        };
    }
}
