package co.com.pragma.transfers.infrastructure.entrypoint.dto;

import co.com.pragma.transfers.model.transfer.PageResult;
import co.com.pragma.transfers.model.transfer.Transfer;
import java.util.List;

/** TransferPage del contrato v1. */
public record TransferPageResponse(List<TransferResponse> content, int page, int size, long totalElements,
                                   int totalPages) {

    public static TransferPageResponse from(PageResult<Transfer> page) {
        return new TransferPageResponse(page.content().stream().map(TransferResponse::from).toList(),
            page.page(), page.size(), page.totalElements(), page.totalPages());
    }
}
