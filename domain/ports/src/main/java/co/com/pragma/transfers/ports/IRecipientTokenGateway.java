package co.com.pragma.transfers.ports;

import co.com.pragma.transfers.model.phone.RecipientToken;
import java.time.Instant;
import reactor.core.publisher.Mono;

/** Tokens de destinatario del pago a celular (tabla recipient-tokens, TTL 5 min). */
public interface IRecipientTokenGateway {

    Mono<RecipientToken> save(RecipientToken recipientToken);

    /** Lectura consistente. @return vacío si el token no existe (o el TTL ya lo borró). */
    Mono<RecipientToken> findById(String token);

    /**
     * RN-12, uso único: escritura condicional que marca el token como usado por la
     * transferencia. Solo pasa si el token es del cliente y está vigente y sin usar,
     * o si ya lo usó esta misma transferencia (reintento con el mismo Idempotency-Key).
     *
     * @return true si el token quedó a nombre de la transferencia; false si no se pudo usar.
     */
    Mono<Boolean> redeem(String token, String customerId, String transferId, Instant now);
}
