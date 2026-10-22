package co.com.pragma.transfers.infrastructure.dynamodb;

import co.com.pragma.transfers.infrastructure.dynamodb.item.RecipientTokenItem;
import co.com.pragma.transfers.model.phone.RecipientToken;
import co.com.pragma.transfers.ports.IRecipientTokenGateway;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletionException;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbAsyncTable;
import software.amazon.awssdk.enhanced.dynamodb.Expression;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.GetItemEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.IgnoreNullsMode;
import software.amazon.awssdk.enhanced.dynamodb.model.UpdateItemEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

/**
 * Tabla recipient-tokens. El uso único (RN-12) es una actualización condicional
 * sobre {@code usedAt}: solo pasa si el token existe, es del cliente y está vigente
 * y sin usar, o si ya lo usó la misma transferencia (reintento idempotente). La
 * condición compara {@code expiresAt} con la hora actual porque el TTL de DynamoDB
 * borra los ítems vencidos con retraso (hasta horas).
 */
public class DynamoRecipientTokenAdapter implements IRecipientTokenGateway {

    static final String REDEEM_CONDITION = "attribute_exists(recipientToken) AND customerId = :customer AND "
        + "((attribute_not_exists(usedAt) AND " + RecipientTokenItem.TTL_ATTRIBUTE + " > :now) "
        + "OR usedByTransferId = :transfer)";

    private final DynamoDbAsyncTable<RecipientTokenItem> table;

    public DynamoRecipientTokenAdapter(DynamoDbAsyncTable<RecipientTokenItem> table) {
        this.table = table;
    }

    @Override
    public Mono<RecipientToken> save(RecipientToken recipientToken) {
        return Mono.fromFuture(() -> table.putItem(RecipientTokenConverter.toItem(recipientToken)))
            .thenReturn(recipientToken);
    }

    @Override
    public Mono<RecipientToken> findById(String token) {
        GetItemEnhancedRequest request = GetItemEnhancedRequest.builder()
            .key(Key.builder().partitionValue(token).build())
            .consistentRead(true)
            .build();
        return Mono.fromFuture(() -> table.getItem(request))
            .map(RecipientTokenConverter::toDomain);
    }

    @Override
    public Mono<Boolean> redeem(String token, String customerId, String transferId, Instant now) {
        RecipientTokenItem update = new RecipientTokenItem();
        update.setRecipientToken(token);
        update.setUsedAt(now.toString());
        update.setUsedByTransferId(transferId);
        Expression condition = Expression.builder()
            .expression(REDEEM_CONDITION)
            .expressionValues(Map.of(
                ":customer", AttributeValue.fromS(customerId),
                ":now", AttributeValue.fromN(Long.toString(now.getEpochSecond())),
                ":transfer", AttributeValue.fromS(transferId)))
            .build();
        // Nulos ignorados: solo se escriben usedAt y usedByTransferId; el resto del ítem no se toca.
        UpdateItemEnhancedRequest<RecipientTokenItem> request = UpdateItemEnhancedRequest
            .builder(RecipientTokenItem.class)
            .item(update)
            .ignoreNullsMode(IgnoreNullsMode.SCALAR_ONLY)
            .conditionExpression(condition)
            .build();
        return Mono.fromFuture(() -> table.updateItem(request))
            .thenReturn(Boolean.TRUE)
            .onErrorResume(DynamoRecipientTokenAdapter::isConditionFailure, error -> Mono.just(Boolean.FALSE));
    }

    private static boolean isConditionFailure(Throwable error) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        return cause instanceof ConditionalCheckFailedException;
    }
}
