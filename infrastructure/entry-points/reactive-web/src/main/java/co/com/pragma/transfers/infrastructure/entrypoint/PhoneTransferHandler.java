package co.com.pragma.transfers.infrastructure.entrypoint;

import co.com.pragma.transfers.infrastructure.entrypoint.dto.CreatePhoneTransferRequest;
import co.com.pragma.transfers.infrastructure.entrypoint.dto.RecipientResolutionResponse;
import co.com.pragma.transfers.infrastructure.helpers.CorrelationWebFilter;
import co.com.pragma.transfers.model.phone.PhoneTransferCommand;
import co.com.pragma.transfers.model.transfer.Money;
import co.com.pragma.transfers.usecase.CreatePhoneTransferUseCase;
import co.com.pragma.transfers.usecase.ResolvePhoneRecipientUseCase;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

/**
 * Handler de pago a celular (HU-03). Misma identidad (X-Customer-Id) e
 * Idempotency-Key que las transferencias de la v1.
 */
@Component
public class PhoneTransferHandler {

    private final ResolvePhoneRecipientUseCase resolveRecipient;
    private final CreatePhoneTransferUseCase createPhoneTransfer;
    private final RequestValidator validator;

    public PhoneTransferHandler(ResolvePhoneRecipientUseCase resolveRecipient,
                                CreatePhoneTransferUseCase createPhoneTransfer, RequestValidator validator) {
        this.resolveRecipient = resolveRecipient;
        this.createPhoneTransfer = createPhoneTransfer;
        this.validator = validator;
    }

    public Mono<ServerResponse> resolveRecipient(ServerRequest request) {
        return Mono.defer(() -> {
            String customerId = TransferHandler.customerId(request);
            String phone = request.queryParam("phone").map(PhoneTransferHandler::normalizePhone).orElse(null);
            return resolveRecipient.execute(phone, customerId)
                .flatMap(token -> ServerResponse.ok().contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(RecipientResolutionResponse.from(token)));
        });
    }

    public Mono<ServerResponse> create(ServerRequest request) {
        return Mono.defer(() -> {
            String customerId = TransferHandler.customerId(request);
            String idempotencyKey = TransferHandler.idempotencyKey(request);
            String correlationId = request.exchange().getAttributeOrDefault(CorrelationWebFilter.TRACE_ID, "");
            return request.bodyToMono(CreatePhoneTransferRequest.class)
                .switchIfEmpty(Mono.error(() -> TransferHandler.validation("body", "es obligatorio")))
                .map(validator::validate)
                .map(body -> new PhoneTransferCommand(idempotencyKey, customerId, correlationId,
                    body.sourceAccountId(), body.recipientToken(),
                    Money.parse(body.amount().value(), body.amount().currency()), body.description()))
                .flatMap(createPhoneTransfer::execute)
                .flatMap(TransferHandler::toResponse);
        });
    }

    /**
     * En un query string un '+' sin codificar llega como espacio ({@code ?phone=+519...}). Si el
     * valor empieza con espacio se interpreta como el '+' del E.164; lo correcto es enviar %2B.
     */
    static String normalizePhone(String raw) {
        return raw.startsWith(" ") ? "+" + raw.substring(1) : raw;
    }
}
