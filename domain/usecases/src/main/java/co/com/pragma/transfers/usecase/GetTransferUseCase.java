package co.com.pragma.transfers.usecase;

import co.com.pragma.transfers.model.exception.BusinessException;
import co.com.pragma.transfers.model.exception.ErrorCatalog;
import co.com.pragma.transfers.model.transfer.Transfer;
import co.com.pragma.transfers.ports.ITransferGateway;
import reactor.core.publisher.Mono;

/** GET /api/v1/transfers/{transferId}: 404 si no existe, 403 si es de otro cliente. */
public class GetTransferUseCase {

    private final ITransferGateway transfers;

    public GetTransferUseCase(ITransferGateway transfers) {
        this.transfers = transfers;
    }

    public Mono<Transfer> execute(String transferId, String customerId) {
        return transfers.findById(transferId)
            .switchIfEmpty(Mono.error(() -> new BusinessException(ErrorCatalog.TRANSFER_NOT_FOUND)))
            .flatMap(transfer -> transfer.belongsTo(customerId)
                ? Mono.just(transfer)
                : Mono.error(new BusinessException(ErrorCatalog.ACCOUNT_NOT_OWNED,
                    "La transferencia no pertenece al cliente")));
    }
}
