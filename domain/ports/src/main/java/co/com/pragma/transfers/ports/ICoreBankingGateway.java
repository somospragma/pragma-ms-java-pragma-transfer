package co.com.pragma.transfers.ports;

import co.com.pragma.transfers.model.account.Account;
import co.com.pragma.transfers.model.account.Posting;
import co.com.pragma.transfers.model.transfer.Money;
import reactor.core.publisher.Mono;

/**
 * Core bancario. Débitos, créditos y reversas son idempotentes por operationId.
 * Errores: BusinessException para rechazos del core (saldo, cuenta bloqueada) y
 * TechnicalException DEPENDENCY_UNAVAILABLE si no responde.
 */
public interface ICoreBankingGateway {

    /** @return vacío si la cuenta no existe. */
    Mono<Account> getAccount(String accountId);

    Mono<Posting> debit(String accountId, String operationId, Money amount, String reference);

    Mono<Posting> credit(String accountId, String operationId, Money amount, String reference);

    Mono<Posting> reverseDebit(String debitPostingId, String operationId, String reason);

    /** Consulta un movimiento tras un timeout. @return vacío si no existe. */
    Mono<Posting> findOperation(String operationId);
}
