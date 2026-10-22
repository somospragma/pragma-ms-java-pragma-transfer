package co.com.pragma.transfers.ports;

import co.com.pragma.transfers.model.event.TransferEvent;
import reactor.core.publisher.Mono;

/** Publicación de eventos de dominio (SNS transfers-events). */
public interface ITransferEventPublisher {

    Mono<Void> publish(TransferEvent event);
}
