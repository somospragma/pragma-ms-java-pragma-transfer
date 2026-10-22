package co.com.pragma.transfers.infrastructure.dynamodb;

import co.com.pragma.transfers.infrastructure.dynamodb.item.TransferItem;
import co.com.pragma.transfers.model.transfer.Transfer;
import co.com.pragma.transfers.model.transfer.TransferStatus;
import co.com.pragma.transfers.ports.ITransferGateway;
import java.math.BigDecimal;
import java.time.LocalDate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbAsyncTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.Page;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;

/** Tabla transfers con el SDK v2 asíncrono (enhanced client). */
public class DynamoTransferAdapter implements ITransferGateway {

    private final DynamoDbAsyncTable<TransferItem> table;

    public DynamoTransferAdapter(DynamoDbAsyncTable<TransferItem> table) {
        this.table = table;
    }

    @Override
    public Mono<Transfer> save(Transfer transfer) {
        return Mono.fromFuture(() -> table.putItem(TransferItemConverter.toItem(transfer)))
            .thenReturn(transfer);
    }

    @Override
    public Mono<Transfer> findById(String transferId) {
        return Mono.fromFuture(() -> table.getItem(Key.builder().partitionValue(transferId).build()))
            .map(TransferItemConverter::toDomain);
    }

    @Override
    public Flux<Transfer> findBySourceAccount(String sourceAccountId) {
        return query(TransferItem.GSI_SOURCE_ACCOUNT, sourceAccountId)
            .map(TransferItemConverter::toDomain);
    }

    @Override
    public Mono<BigDecimal> sumDailyAmount(String customerId, LocalDate businessDate, String currency) {
        return query(TransferItem.GSI_CUSTOMER_DAY, TransferItemConverter.customerDay(customerId, businessDate))
            .filter(item -> currency.equals(item.getCurrency()))
            .filter(item -> TransferStatus.valueOf(item.getStatus()).countsTowardDailyLimit())
            .map(item -> new BigDecimal(item.getAmount()))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Consulta un GSI ordenado por createdAt descendente, recorriendo todas las páginas. */
    private Flux<TransferItem> query(String indexName, String partitionValue) {
        QueryEnhancedRequest request = QueryEnhancedRequest.builder()
            .queryConditional(QueryConditional.keyEqualTo(Key.builder().partitionValue(partitionValue).build()))
            .scanIndexForward(false)
            .build();
        return Flux.defer(() -> Flux.from(table.index(indexName).query(request)))
            .flatMapIterable(Page::items);
    }
}
