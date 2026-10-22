package co.com.pragma.transfers.usecase;

import co.com.pragma.transfers.model.exception.BusinessException;
import co.com.pragma.transfers.model.exception.ErrorCatalog;
import co.com.pragma.transfers.model.transfer.PageResult;
import co.com.pragma.transfers.model.transfer.Transfer;
import co.com.pragma.transfers.model.transfer.TransferPolicy;
import co.com.pragma.transfers.model.transfer.TransferStatus;
import co.com.pragma.transfers.ports.ICoreBankingGateway;
import co.com.pragma.transfers.ports.ITransferGateway;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.Map;
import reactor.core.publisher.Mono;

/**
 * GET /api/v1/accounts/{accountId}/transfers: historial de la cuenta como
 * origen, del más reciente al más antiguo, con filtros opcionales y paginado.
 * La cuenta debe ser del cliente (403 ACCOUNT_NOT_OWNED).
 */
public class ListAccountTransfersUseCase {

    public static final int MAX_PAGE_SIZE = 100;

    private final ICoreBankingGateway core;
    private final ITransferGateway transfers;
    private final TransferPolicy policy;

    public ListAccountTransfersUseCase(ICoreBankingGateway core, ITransferGateway transfers, TransferPolicy policy) {
        this.core = core;
        this.transfers = transfers;
        this.policy = policy;
    }

    public Mono<PageResult<Transfer>> execute(String accountId, String customerId, TransferStatus status,
                                              LocalDate from, LocalDate to, int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            return Mono.error(new BusinessException(ErrorCatalog.VALIDATION_ERROR,
                "page debe ser >= 0 y size entre 1 y " + MAX_PAGE_SIZE,
                Map.of("page/size", "fuera de rango")));
        }
        if (from != null && to != null && from.isAfter(to)) {
            return Mono.error(new BusinessException(ErrorCatalog.VALIDATION_ERROR,
                "from no puede ser posterior a to", Map.of("from", "posterior a to")));
        }
        return core.getAccount(accountId)
            .filter(account -> account.isOwnedBy(customerId))
            .switchIfEmpty(Mono.error(() -> new BusinessException(ErrorCatalog.ACCOUNT_NOT_OWNED)))
            .flatMap(account -> transfers.findBySourceAccount(accountId)
                .filter(t -> status == null || t.status() == status)
                .filter(t -> from == null || !policy.businessDate(t.createdAt()).isBefore(from))
                .filter(t -> to == null || !policy.businessDate(t.createdAt()).isAfter(to))
                .sort(Comparator.comparing(Transfer::createdAt).reversed())
                .collectList()
                .map(all -> PageResult.of(all, page, size)));
    }
}
