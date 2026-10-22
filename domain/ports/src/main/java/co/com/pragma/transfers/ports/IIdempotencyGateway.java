package co.com.pragma.transfers.ports;

import co.com.pragma.transfers.model.idempotency.IdempotencyRecord;
import reactor.core.publisher.Mono;

/** Llaves de idempotencia (tabla idempotency, TTL 24 h). */
public interface IIdempotencyGateway {

    /**
     * Escritura condicional: guarda la reserva si la llave no existe (o ya venció).
     *
     * @return vacío si la reserva quedó guardada; el registro existente si la llave ya estaba usada.
     */
    Mono<IdempotencyRecord> putIfAbsent(IdempotencyRecord idempotencyRecord);
}
