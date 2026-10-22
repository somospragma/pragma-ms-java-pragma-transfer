package co.com.pragma.transfers.infrastructure.dynamodb;

import co.com.pragma.transfers.infrastructure.dynamodb.item.IdempotencyItem;
import co.com.pragma.transfers.model.idempotency.IdempotencyRecord;
import co.com.pragma.transfers.ports.IIdempotencyGateway;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.CompletionException;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbAsyncTable;
import software.amazon.awssdk.enhanced.dynamodb.Expression;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.GetItemEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.PutItemEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

/**
 * Tabla idempotency. La reserva es una escritura condicional: solo se guarda si
 * la llave no existe o ya venció (el TTL de DynamoDB borra con retraso).
 */
public class DynamoIdempotencyAdapter implements IIdempotencyGateway {

    private static final String CONDITION =
        "attribute_not_exists(idempotencyKey) OR " + IdempotencyItem.TTL_ATTRIBUTE + " <= :now";

    private final DynamoDbAsyncTable<IdempotencyItem> table;
    private final Clock clock;

    public DynamoIdempotencyAdapter(DynamoDbAsyncTable<IdempotencyItem> table, Clock clock) {
        this.table = table;
        this.clock = clock;
    }

    @Override
    public Mono<IdempotencyRecord> putIfAbsent(IdempotencyRecord idempotencyRecord) {
        Expression condition = Expression.builder()
            .expression(CONDITION)
            .expressionValues(Map.of(":now", AttributeValue.fromN(Long.toString(clock.instant().getEpochSecond()))))
            .build();
        PutItemEnhancedRequest<IdempotencyItem> request = PutItemEnhancedRequest.builder(IdempotencyItem.class)
            .item(TransferItemConverter.toItem(idempotencyRecord))
            .conditionExpression(condition)
            .build();
        return Mono.fromFuture(() -> table.putItem(request))
            .then(Mono.<IdempotencyRecord>empty())
            .onErrorResume(DynamoIdempotencyAdapter::isConditionFailure,
                error -> find(idempotencyRecord.idempotencyKey()));
    }

    private Mono<IdempotencyRecord> find(String idempotencyKey) {
        GetItemEnhancedRequest request = GetItemEnhancedRequest.builder()
            .key(Key.builder().partitionValue(idempotencyKey).build())
            .consistentRead(true)
            .build();
        return Mono.fromFuture(() -> table.getItem(request))
            .map(TransferItemConverter::toDomain);
    }

    private static boolean isConditionFailure(Throwable error) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        return cause instanceof ConditionalCheckFailedException;
    }
}
