package co.com.pragma.transfers.infrastructure.client;

import co.com.pragma.transfers.infrastructure.client.dto.CoreAccountDto;
import co.com.pragma.transfers.infrastructure.client.dto.CoreErrorDto;
import co.com.pragma.transfers.infrastructure.client.dto.MoneyDto;
import co.com.pragma.transfers.infrastructure.client.dto.PostingDto;
import co.com.pragma.transfers.infrastructure.client.dto.PostingRequestDto;
import co.com.pragma.transfers.infrastructure.client.dto.ReversalRequestDto;
import co.com.pragma.transfers.model.account.Account;
import co.com.pragma.transfers.model.account.AccountStatus;
import co.com.pragma.transfers.model.account.Posting;
import co.com.pragma.transfers.model.exception.BusinessException;
import co.com.pragma.transfers.model.exception.ErrorCatalog;
import co.com.pragma.transfers.model.transfer.Money;
import co.com.pragma.transfers.ports.ICoreBankingGateway;
import java.math.BigDecimal;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/** Adaptador del core bancario (openapi/core-banking.yaml). */
public class CoreBankingAdapter implements ICoreBankingGateway {

    private final WebClient client;
    private final OutboundCalls calls;

    public CoreBankingAdapter(WebClient coreBankingWebClient, OutboundCalls calls) {
        this.client = coreBankingWebClient;
        this.calls = calls;
    }

    @Override
    public Mono<Account> getAccount(String accountId) {
        return calls.read(correlationId -> client.get()
                .uri("/core/v1/accounts/{accountId}", accountId)
                .headers(OutboundCalls.correlation(correlationId))
                .exchangeToMono(response -> body(response, CoreAccountDto.class, true)))
            .map(CoreBankingAdapter::toAccount);
    }

    @Override
    public Mono<Posting> debit(String accountId, String operationId, Money amount, String reference) {
        return posting("/core/v1/accounts/{accountId}/debits", accountId,
            new PostingRequestDto(operationId, toDto(amount), reference));
    }

    @Override
    public Mono<Posting> credit(String accountId, String operationId, Money amount, String reference) {
        return posting("/core/v1/accounts/{accountId}/credits", accountId,
            new PostingRequestDto(operationId, toDto(amount), reference));
    }

    @Override
    public Mono<Posting> reverseDebit(String debitPostingId, String operationId, String reason) {
        return calls.write(correlationId -> client.post()
                .uri("/core/v1/debits/{debitId}/reversal", debitPostingId)
                .headers(OutboundCalls.correlation(correlationId))
                .bodyValue(new ReversalRequestDto(operationId, reason))
                .exchangeToMono(response -> body(response, PostingDto.class, false)))
            .map(CoreBankingAdapter::toPosting);
    }

    @Override
    public Mono<Posting> findOperation(String operationId) {
        return calls.read(correlationId -> client.get()
                .uri("/core/v1/operations/{operationId}", operationId)
                .headers(OutboundCalls.correlation(correlationId))
                .exchangeToMono(response -> body(response, PostingDto.class, true)))
            .map(CoreBankingAdapter::toPosting);
    }

    private Mono<Posting> posting(String path, String accountId, PostingRequestDto request) {
        return calls.write(correlationId -> client.post()
                .uri(path, accountId)
                .headers(OutboundCalls.correlation(correlationId))
                .bodyValue(request)
                .exchangeToMono(response -> body(response, PostingDto.class, false)))
            .map(CoreBankingAdapter::toPosting);
    }

    private <T> Mono<T> body(ClientResponse response, Class<T> type, boolean notFoundIsEmpty) {
        int status = response.statusCode().value();
        if (response.statusCode().is2xxSuccessful()) {
            return response.bodyToMono(type);
        }
        if (notFoundIsEmpty && status == HttpStatus.NOT_FOUND.value()) {
            return response.releaseBody().then(Mono.empty());
        }
        if (response.statusCode().is4xxClientError()) {
            return response.bodyToMono(CoreErrorDto.class)
                .onErrorResume(error -> Mono.empty())
                .defaultIfEmpty(new CoreErrorDto(null, null))
                .flatMap(error -> Mono.error(toBusiness(error)));
        }
        return response.releaseBody().then(Mono.error(calls.unavailable(status)));
    }

    /** Rechazos del core: saldo insuficiente o cuenta no operable. */
    static BusinessException toBusiness(CoreErrorDto error) {
        if ("INSUFFICIENT_FUNDS".equals(error.code())) {
            return new BusinessException(ErrorCatalog.INSUFFICIENT_FUNDS);
        }
        return new BusinessException(ErrorCatalog.ACCOUNT_INVALID);
    }

    static Account toAccount(CoreAccountDto dto) {
        Money balance = dto.availableBalance() == null
            ? null
            : new Money(new BigDecimal(dto.availableBalance().value()), dto.availableBalance().currency());
        return new Account(dto.accountId(), dto.customerId(), toStatus(dto.status()), dto.currency(), balance);
    }

    private static AccountStatus toStatus(String status) {
        try {
            return AccountStatus.valueOf(status);
        } catch (RuntimeException e) {
            return AccountStatus.BLOCKED;
        }
    }

    static Posting toPosting(PostingDto dto) {
        return new Posting(dto.postingId(), dto.operationId(), dto.type(), dto.status());
    }

    static MoneyDto toDto(Money money) {
        return new MoneyDto(money.asString(), money.currency());
    }
}
