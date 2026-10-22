package co.com.pragma.transfers.usecase;

import co.com.pragma.transfers.model.exception.BusinessException;
import co.com.pragma.transfers.model.exception.ErrorCatalog;
import co.com.pragma.transfers.model.phone.PhoneTransferCommand;
import co.com.pragma.transfers.model.phone.PhoneTransferPolicy;
import co.com.pragma.transfers.model.phone.RecipientToken;
import co.com.pragma.transfers.model.transfer.Transfer;
import co.com.pragma.transfers.model.transfer.TransferChannel;
import co.com.pragma.transfers.model.transfer.TransferPolicy;
import co.com.pragma.transfers.model.transfer.TransferResult;
import co.com.pragma.transfers.model.transfer.TransferStatus;
import co.com.pragma.transfers.ports.ICoreBankingGateway;
import co.com.pragma.transfers.ports.IRecipientTokenGateway;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import reactor.core.publisher.Mono;

/**
 * POST /api/v1/phone-transfers. Antes de mover dinero aplica, en este orden:
 * RN-12 (token vigente, sin usar y del mismo cliente) → cuenta origen del cliente → RN-13 (el destinatario
 * no es el mismo cliente) → RN-11 (hasta S/ 500.00 y solo PEN) → uso único del
 * token (escritura condicional). Luego reutiliza el flujo de la v1
 * ({@link CreateTransferUseCase}: RN-08 idempotencia, RN-03, RN-05 a RN-07,
 * SAGA débito → crédito con compensación y eventos) con {@code channel = PHONE}.
 *
 * <p>La idempotencia va primero: el reintento de un pago ya hecho devuelve 200 con
 * el pago original aunque el token ya esté usado. Los rechazos de RN-11 a RN-13 no
 * consumen el token ni guardan transferencia; tampoco el 403 por cuenta origen ajena.
 */
public class CreatePhoneTransferUseCase {

    private final CreateTransferUseCase transferFlow;
    private final IRecipientTokenGateway tokens;
    private final ICoreBankingGateway core;
    private final PhoneTransferPolicy phonePolicy;
    private final TransferPolicy policy;
    private final Clock clock;

    public CreatePhoneTransferUseCase(CreateTransferUseCase transferFlow, IRecipientTokenGateway tokens,
                                      ICoreBankingGateway core, PhoneTransferPolicy phonePolicy,
                                      TransferPolicy policy, Clock clock) {
        this.transferFlow = transferFlow;
        this.tokens = tokens;
        this.core = core;
        this.phonePolicy = phonePolicy;
        this.policy = policy;
        this.clock = clock;
    }

    public Mono<TransferResult> execute(PhoneTransferCommand command) {
        return transferFlow.execute(command.idempotencyKey(), command.fingerprint(),
            transferId -> prepare(transferId, command));
    }

    private Mono<Transfer> prepare(String transferId, PhoneTransferCommand command) {
        Instant now = clock.instant();
        return tokens.findById(command.recipientToken())
            .filter(token -> token.isRedeemableBy(command.customerId(), transferId, now))
            .switchIfEmpty(Mono.error(CreatePhoneTransferUseCase::tokenExpired))
            .flatMap(token -> validateSource(command)
                .then(Mono.defer(() -> validateRecipient(token, command)))
                .then(Mono.defer(() -> validateLimit(command)))
                .then(Mono.defer(() -> tokens.redeem(token.token(), command.customerId(), transferId, now)))
                .flatMap(redeemed -> Boolean.TRUE.equals(redeemed)
                    ? Mono.just(newTransfer(transferId, command, token, now))
                    // Otro pago lo usó o venció entre la lectura y la escritura condicional.
                    : Mono.error(tokenExpired())));
    }

    /** La cuenta origen debe ser del cliente; se valida antes de consumir el token. */
    private Mono<Void> validateSource(PhoneTransferCommand command) {
        return core.getAccount(command.sourceAccountId())
            .filter(source -> source.isOwnedBy(command.customerId()))
            .switchIfEmpty(Mono.error(() -> new BusinessException(ErrorCatalog.ACCOUNT_NOT_OWNED)))
            .then();
    }

    /** RN-13: no se puede pagar a un celular del mismo cliente (cuenta destino propia). */
    private Mono<Void> validateRecipient(RecipientToken token, PhoneTransferCommand command) {
        if (token.targetAccountId().equals(command.sourceAccountId())) {
            return Mono.error(selfTransfer());
        }
        return core.getAccount(token.targetAccountId())
            .filter(target -> target.isOwnedBy(command.customerId()))
            .flatMap(target -> Mono.<Void>error(selfTransfer()));
    }

    /** RN-11: solo soles y hasta el máximo por pago a celular. */
    private Mono<Void> validateLimit(PhoneTransferCommand command) {
        if (!phonePolicy.allowsCurrency(command.amount())) {
            return Mono.error(new BusinessException(ErrorCatalog.LIMIT_PER_TX_EXCEEDED,
                "El pago a celular solo admite " + phonePolicy.currency()));
        }
        if (phonePolicy.exceedsLimit(command.amount())) {
            return Mono.error(new BusinessException(ErrorCatalog.LIMIT_PER_TX_EXCEEDED,
                "El máximo por pago a celular es S/ "
                    + phonePolicy.perTransactionLimit().setScale(2, RoundingMode.HALF_UP).toPlainString()));
        }
        return Mono.empty();
    }

    private Transfer newTransfer(String transferId, PhoneTransferCommand command, RecipientToken token,
                                 Instant now) {
        return Transfer.builder()
            .transferId(transferId)
            .idempotencyKey(command.idempotencyKey())
            .customerId(command.customerId())
            .sourceAccountId(command.sourceAccountId())
            .targetAccountId(token.targetAccountId())
            .targetPhoneMasked(token.targetPhoneMasked())
            .amount(command.amount())
            .description(command.description())
            .status(TransferStatus.RECEIVED)
            .channel(TransferChannel.PHONE)
            .correlationId(command.correlationId())
            .businessDate(policy.businessDate(now))
            .createdAt(now)
            .updatedAt(now)
            .build();
    }

    private static BusinessException tokenExpired() {
        return new BusinessException(ErrorCatalog.RECIPIENT_TOKEN_EXPIRED);
    }

    private static BusinessException selfTransfer() {
        return new BusinessException(ErrorCatalog.SELF_TRANSFER);
    }
}
