package co.com.pragma.transfers.infrastructure.dynamodb;

import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedAsyncClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;

/**
 * Wiring del cliente DynamoDB async enhanced. Se activa solo si dynamodb.enabled
 * = true (para no exigir DynamoDB al arrancar en local). Las credenciales las
 * resuelve el DefaultCredentialsProvider (task role de ECS en runtime).
 * Las tablas transfers e idempotency se registran aqui en la HU-01.
 */
@Configuration
@ConditionalOnProperty(prefix = "dynamodb", name = "enabled", havingValue = "true")
public class DynamoDbConfig {

    @Bean
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
}
