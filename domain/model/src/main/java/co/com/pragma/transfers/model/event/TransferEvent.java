package co.com.pragma.transfers.model.event;

import co.com.pragma.transfers.model.transfer.Money;
import co.com.pragma.transfers.model.transfer.Transfer;
import java.time.Instant;

/** Evento de dominio TransferEvent (asyncapi). */
public record TransferEvent(String eventId, TransferEventType eventType, String transferId, String customerId,
                            String channel, Money amount, String failureCode, Instant occurredAt,
                            String correlationId) {

    public static TransferEvent of(String eventId, TransferEventType type, Transfer transfer, Instant occurredAt) {
        return new TransferEvent(eventId, type, transfer.transferId(), transfer.customerId(),
            transfer.channel().name(), transfer.amount(), transfer.failureCode(), occurredAt,
            transfer.correlationId());
    }
}
