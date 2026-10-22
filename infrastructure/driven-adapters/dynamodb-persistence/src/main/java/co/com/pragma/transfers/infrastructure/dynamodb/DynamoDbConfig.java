package co.com.pragma.transfers.infrastructure.dynamodb;

import co.com.pragma.transfers.infrastructure.dynamodb.item.IdempotencyItem;
import co.com.pragma.transfers.infrastructure.dynamodb.item.TransferItem;
import co.com.pragma.transfers.ports.IIdempotencyGateway;
import co.com.pragma.transfers.ports.ITransferGateway;
import java.net.URI;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbAsyncTable;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedAsyncClient;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;

/**
 * Wiring del cliente DynamoDB async enhanced y de las tablas transfers e
 * idempotency (nombres por configuración). Se activa solo si dynamodb.enabled
 * = true; las credenciales las resuelve el DefaultCredentialsProvider (task role
 * de ECS en runtime). Con la bandera en false se usa InMemoryPersistenceConfig.
 */
@Configuration
@ConditionalOnProperty(prefix = "dynamodb", name = "enabled", havingValue = "true")
public class DynamoDbConfig {

    @Bean(destroyMethod = "close")
    public DynamoDbAsyncClient dynamoDbAsyncClient(
            @Value("${dynamodb.endpoint:}") String endpoint) {
        var builder = DynamoDbAsyncClient.builder();
        if (endpoint != null && !endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint));
        }
        return builder.build();
    }

    @Bean
    public DynamoDbEnhancedAsyncClient dynamoDbEnhancedAsyncClient(DynamoDbAsyncClient client) {
        return DynamoDbEnhancedAsyncClient.builder().dynamoDbClient(client).build();
    }

    @Bean
    public ITransferGateway dynamoTransferAdapter(DynamoDbEnhancedAsyncClient client,
            @Value("${dynamodb.table.transfers}") String tableName) {
        DynamoDbAsyncTable<TransferItem> table = client.table(tableName, TableSchema.fromBean(TransferItem.class));
        return new DynamoTransferAdapter(table);
    }

    @Bean
    public IIdempotencyGateway dynamoIdempotencyAdapter(DynamoDbEnhancedAsyncClient client,
            @Value("${dynamodb.table.idempotency}") String tableName, Clock clock) {
        DynamoDbAsyncTable<IdempotencyItem> table =
            client.table(tableName, TableSchema.fromBean(IdempotencyItem.class));
        return new DynamoIdempotencyAdapter(table, clock);
    }
}
