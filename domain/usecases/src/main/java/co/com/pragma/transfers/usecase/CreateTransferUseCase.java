package co.com.pragma.transfers.usecase;

import co.com.pragma.transfers.model.account.Account;
import co.com.pragma.transfers.model.account.Posting;
import co.com.pragma.transfers.model.event.TransferEvent;
import co.com.pragma.transfers.model.event.TransferEventType;
import co.com.pragma.transfers.model.exception.BusinessException;
import co.com.pragma.transfers.model.exception.ErrorCatalog;
import co.com.pragma.transfers.model.fraud.FraudAssessment;
import co.com.pragma.transfers.model.fraud.FraudDecision;
import co.com.pragma.transfers.model.idempotency.IdempotencyRecord;
import co.com.pragma.transfers.model.transfer.Transfer;
import co.com.pragma.transfers.model.transfer.TransferChannel;
import co.com.pragma.transfers.model.transfer.TransferCommand;
import co.com.pragma.transfers.model.transfer.TransferPolicy;
import co.com.pragma.transfers.model.transfer.TransferResult;
import co.com.pragma.transfers.model.transfer.TransferStatus;
import co.com.pragma.transfers.ports.ICoreBankingGateway;
import co.com.pragma.transfers.ports.IFraudGateway;
import co.com.pragma.transfers.ports.IIdempotencyGateway;
import co.com.pragma.transfers.ports.ITransferEventPublisher;
import co.com.pragma.transfers.ports.ITransferGateway;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import reactor.core.publisher.Mono;

/**
 * POST /api/v1/transfers. Aplica RN-01 a RN-08 y la SAGA débito → crédito con
 * compensación (reversa del débito si el crédito falla). El pago a celular
 * (HU-03) reutiliza este mismo flujo con {@code channel = PHONE} a través de
 * {@link #execute(String, String, Function)}.
 *
 * <p>Orden de evaluación: idempotencia (RN-08) → propiedad de la cuenta origen
 * (403) → límite por operación (RN-04) → límite diario (RN-05) → cuentas activas
 * (RN-01) → misma moneda (RN-02) → antifraude (RN-07) → saldo (RN-06) → SAGA.
 * El saldo se valida después de antifraude porque PENDING_REVIEW no mueve dinero.
 *
 * <p>Si una dependencia falla a mitad de camino (503), la transferencia queda en
 * RECEIVED o DEBITED y un reintento con el mismo Idempotency-Key la retoma con
 * los mismos operationId (el core los deduplica).
 */
public class CreateTransferUseCase {

    private static final Logger LOG = Logger.getLogger(CreateTransferUseCase.class.getName());
    static final String CREDIT_FAILED = "CREDIT_FAILED";

    private final ITransferGateway transfers;
    private final IIdempotencyGateway idempotency;
    private final ICoreBankingGateway core;
    private final IFraudGateway fraud;
    private final ITransferEventPublisher events;
    private final TransferPolicy policy;
    private final Clock clock;

    public CreateTransferUseCase(ITransferGateway transfers, IIdempotencyGateway idempotency,
                                 ICoreBankingGateway core, IFraudGateway fraud,
                                 ITransferEventPublisher events, TransferPolicy policy, Clock clock) {
        this.transfers = transfers;
        this.idempotency = idempotency;
        this.core = core;
        this.fraud = fraud;
        this.events = events;
        this.policy = policy;
        this.clock = clock;
    }

    public Mono<TransferResult> execute(TransferCommand command) {
        return execute(command.idempotencyKey(), command.fingerprint(),
            transferId -> Mono.fromSupplier(() -> newTransfer(transferId, command)));
    }

    /**
     * Flujo común a todos los canales (cuenta y celular): RN-08, reglas de la v1 y SAGA.
     *
     * @param prepare arma la transferencia nueva con el transferId reservado. Puede fallar con
     *                un rechazo propio del canal (p. ej. RN-12 en pago a celular) antes de que
     *                se guarde o se mueva dinero; ese rechazo se repite si llega la misma llave.
     */
    public Mono<TransferResult> execute(String idempotencyKey, String fingerprint,
                                        Function<String, Mono<Transfer>> prepare) {
        return Mono.defer(() -> {
            Instant now = clock.instant();
            String transferId = UUID.randomUUID().toString();
            IdempotencyRecord reservation = new IdempotencyRecord(idempotencyKey, fingerprint, transferId, now,
                now.plus(policy.idempotencyTtl()));
            return idempotency.putIfAbsent(reservation)
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .flatMap(existing -> existing
                    .map(previous -> replay(previous, fingerprint, prepare))
                    .orElseGet(() -> createNew(transferId, prepare)));
        });
    }

    private Mono<TransferResult> createNew(String transferId, Function<String, Mono<Transfer>> prepare) {
        return prepare.apply(transferId)
            .flatMap(transfer -> process(transfer, false))
            .map(TransferResult::created);
    }

    // ---------------------------------------------------------------- RN-08

    private Mono<TransferResult> replay(IdempotencyRecord previous, String fingerprint,
                                        Function<String, Mono<Transfer>> prepare) {
        if (!previous.matches(fingerprint)) {
            return Mono.error(new BusinessException(ErrorCatalog.IDEMPOTENCY_KEY_REUSED));
        }
        return transfers.findById(previous.transferId())
            .flatMap(this::replayExisting)
            // La reserva existe pero la transferencia no llegó a guardarse (p. ej. 403, un rechazo
            // previo del canal o una caída): se procesa de nuevo con el mismo transferId.
            .switchIfEmpty(Mono.defer(() -> createNew(previous.transferId(), prepare)));
    }

    private Mono<TransferResult> replayExisting(Transfer existing) {
        if (existing.status() == TransferStatus.REJECTED) {
            return Mono.error(rejection(existing.failureCode()));
        }
        if (existing.status().isFinal()) {
            return Mono.just(TransferResult.replayed(existing));
        }
        return resume(existing).map(TransferResult::created);
    }

    /** Retoma una transferencia que quedó a mitad de la SAGA por una falla técnica. */
    private Mono<Transfer> resume(Transfer pending) {
        if (pending.status() == TransferStatus.DEBITED) {
            return credit(pending);
        }
        // RECEIVED: el débito pudo aplicarse aunque la llamada haya fallado (timeout).
        return core.findOperation(pending.debitOperationId())
            .flatMap(posting -> transfers.save(pending.toBuilder()
                    .status(TransferStatus.DEBITED)
                    .debitPostingId(posting.postingId())
                    .updatedAt(clock.instant())
                    .build())
                .flatMap(this::credit))
            .switchIfEmpty(Mono.defer(() -> process(pending, true)));
    }

    // ------------------------------------------------------------- reglas

    /**
     * @param persisted true si la transferencia ya está guardada (retoma de un RECEIVED); su monto
     *                  ya suma en el acumulado diario y no se cuenta dos veces.
     */
    private Mono<Transfer> process(Transfer transfer, boolean persisted) {
        return Mono.zip(core.getAccount(transfer.sourceAccountId()).map(Optional::of)
                    .defaultIfEmpty(Optional.empty()),
                core.getAccount(transfer.targetAccountId()).map(Optional::of)
                    .defaultIfEmpty(Optional.empty()))
            .flatMap(accounts -> {
                Optional<Account> source = accounts.getT1();
                Optional<Account> target = accounts.getT2();
                if (source.isEmpty() || !source.get().isOwnedBy(transfer.customerId())) {
                    return Mono.error(new BusinessException(ErrorCatalog.ACCOUNT_NOT_OWNED));
                }
                return validateLimits(transfer, persisted)
                    .then(Mono.defer(() -> validateAccounts(transfer, source.get(), target.orElse(null))))
                    .switchIfEmpty(Mono.defer(() -> evaluateFraud(transfer, source.get())));
            });
    }

    /** RN-04 y RN-05. Completa vacío si pasan; si no, rechaza. */
    private Mono<Transfer> validateLimits(Transfer transfer, boolean persisted) {
        if (policy.exceedsPerTransaction(transfer.amount())) {
            return reject(transfer, ErrorCatalog.LIMIT_PER_TX_EXCEEDED);
        }
        return transfers.sumDailyAmount(transfer.customerId(), transfer.businessDate(),
                transfer.amount().currency())
            .defaultIfEmpty(BigDecimal.ZERO)
            .map(accumulated -> persisted ? accumulated.subtract(transfer.amount().value()) : accumulated)
            .flatMap(accumulated -> policy.exceedsDaily(accumulated, transfer.amount())
                ? reject(transfer, ErrorCatalog.DAILY_LIMIT_EXCEEDED)
                : Mono.<Transfer>empty());
    }

    /** RN-01 y RN-02. Completa vacío si pasan; si no, rechaza. */
    private Mono<Transfer> validateAccounts(Transfer transfer, Account source, Account target) {
        if (target == null || !source.isActive() || !target.isActive()
            || source.accountId().equals(target.accountId())) {
            return reject(transfer, ErrorCatalog.ACCOUNT_INVALID);
        }
        if (!source.currency().equals(target.currency()) || !transfer.amount().sameCurrency(source.currency())) {
            return reject(transfer, ErrorCatalog.CURRENCY_MISMATCH);
        }
        return Mono.empty();
    }

    /** RN-07 (y RN-06 si aprueba). Un error o timeout de antifraude deja la transferencia en revisión. */
    private Mono<Transfer> evaluateFraud(Transfer transfer, Account source) {
        return fraud.score(transfer)
            .map(Optional::of)
            .onErrorResume(error -> {
                LOG.log(Level.WARNING, "Antifraude no respondió para {0}; queda PENDING_REVIEW",
                    transfer.transferId());
                return Mono.just(Optional.empty());
            })
            .defaultIfEmpty(Optional.empty())
            .flatMap(assessment -> {
                FraudDecision decision = assessment.map(FraudAssessment::decision).orElse(FraudDecision.REVIEW);
                Transfer scored = transfer.toBuilder()
                    .fraudScore(assessment.map(FraudAssessment::score).orElse(null))
                    .build();
                // if en lugar de switch sobre el enum: el switch genera una clase sintética ($1)
                // que la regla de ArchUnit (sufijo UseCase en el paquete usecase) rechaza.
                if (decision == FraudDecision.REJECT) {
                    return reject(scored, ErrorCatalog.FRAUD_REJECTED);
                }
                if (decision == FraudDecision.REVIEW) {
                    return finish(scored.withStatus(TransferStatus.PENDING_REVIEW, clock.instant()));
                }
                return source.canCover(scored.amount())
                    ? debit(scored)
                    : reject(scored, ErrorCatalog.INSUFFICIENT_FUNDS);
            });
    }

    // --------------------------------------------------------------- SAGA

    private Mono<Transfer> debit(Transfer transfer) {
        return transfers.save(transfer.withStatus(TransferStatus.RECEIVED, clock.instant()))
            .flatMap(received -> core.debit(received.sourceAccountId(), received.debitOperationId(),
                    received.amount(), reference(received))
                .onErrorResume(BusinessException.class, rejected -> reject(received, rejected.code())
                    .then(Mono.<Posting>empty()))
                .flatMap(posting -> transfers.save(received.toBuilder()
                    .status(TransferStatus.DEBITED)
                    .debitPostingId(posting.postingId())
                    .updatedAt(clock.instant())
                    .build()))
                .flatMap(this::credit));
    }

    private Mono<Transfer> credit(Transfer debited) {
        return core.credit(debited.targetAccountId(), debited.creditOperationId(), debited.amount(),
                reference(debited))
            .map(Optional::of)
            .onErrorResume(error -> {
                LOG.log(Level.WARNING, "Crédito falló para {0}; se reversa el débito", debited.transferId());
                return Mono.just(Optional.empty());
            })
            .defaultIfEmpty(Optional.empty())
            .flatMap(posting -> posting
                .map(credited -> finish(debited.toBuilder()
                    .status(TransferStatus.COMPLETED)
                    .creditPostingId(credited.postingId())
                    .updatedAt(clock.instant())
                    .build()))
                .orElseGet(() -> compensate(debited)));
    }

    /** Si la reversa falla, la transferencia queda DEBITED y el error (503) permite reintentar. */
    private Mono<Transfer> compensate(Transfer debited) {
        return core.reverseDebit(debited.debitPostingId(), debited.reversalOperationId(), CREDIT_FAILED)
            .then(Mono.defer(() -> finish(debited.toBuilder()
                .status(TransferStatus.COMPENSATED)
                .failureCode(CREDIT_FAILED)
                .updatedAt(clock.instant())
                .build())));
    }

    // ----------------------------------------------------------- utilidades

    private Mono<Transfer> reject(Transfer transfer, ErrorCatalog error) {
        return reject(transfer, error.code());
    }

    /** Guarda la transferencia REJECTED, publica el evento y responde el error de la regla. */
    private Mono<Transfer> reject(Transfer transfer, String failureCode) {
        Transfer rejected = transfer.toBuilder()
            .status(TransferStatus.REJECTED)
            .failureCode(failureCode)
            .updatedAt(clock.instant())
            .build();
        return finish(rejected).then(Mono.error(rejection(failureCode)));
    }

    private Mono<Transfer> finish(Transfer terminal) {
        return transfers.save(terminal).flatMap(saved -> publish(saved).thenReturn(saved));
    }

    /** Un evento que no se pudo publicar no revierte la transferencia (se registra en el log). */
    private Mono<Void> publish(Transfer transfer) {
        return TransferEventType.forStatus(transfer.status())
            .map(type -> events.publish(TransferEvent.of(UUID.randomUUID().toString(), type, transfer,
                    clock.instant()))
                .onErrorResume(error -> {
                    LOG.log(Level.SEVERE, "No se pudo publicar {0} de {1}",
                        new Object[] {type, transfer.transferId()});
                    return Mono.empty();
                }))
            .orElseGet(Mono::empty);
    }

    private static BusinessException rejection(String code) {
        return ErrorCatalog.fromCode(code)
            .map(BusinessException::new)
            .orElseGet(() -> new BusinessException(code, "Transferencia rechazada"));
    }

    private Transfer newTransfer(String transferId, TransferCommand command) {
        Instant now = clock.instant();
        return Transfer.builder()
            .transferId(transferId)
            .idempotencyKey(command.idempotencyKey())
            .customerId(command.customerId())
            .sourceAccountId(command.sourceAccountId())
            .targetAccountId(command.targetAccountId())
            .amount(command.amount())
            .description(command.description())
            .status(TransferStatus.RECEIVED)
            .channel(TransferChannel.ACCOUNT)
            .correlationId(command.correlationId())
            .businessDate(policy.businessDate(now))
            .createdAt(now)
            .updatedAt(now)
            .build();
    }

    private static String reference(Transfer transfer) {
        return "Transferencia " + transfer.transferId();
    }
}
