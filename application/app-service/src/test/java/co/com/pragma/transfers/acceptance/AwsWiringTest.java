package co.com.pragma.transfers.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import co.com.pragma.transfers.application.MainApplication;
import co.com.pragma.transfers.infrastructure.dynamodb.DynamoIdempotencyAdapter;
import co.com.pragma.transfers.infrastructure.dynamodb.DynamoRecipientTokenAdapter;
import co.com.pragma.transfers.infrastructure.dynamodb.DynamoTransferAdapter;
import co.com.pragma.transfers.infrastructure.sns.SnsTransferEventPublisher;
import co.com.pragma.transfers.ports.IIdempotencyGateway;
import co.com.pragma.transfers.ports.IRecipientTokenGateway;
import co.com.pragma.transfers.ports.ITransferEventPublisher;
import co.com.pragma.transfers.ports.ITransferGateway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Con dynamodb.enabled y sns.enabled en true el contexto arma los adaptadores
 * reales (esquemas de tabla con sus GSI y cliente SNS) sin conectarse a AWS: el
 * SDK no abre conexión hasta la primera operación. Sin Docker no se prueba
 * contra DynamoDB/SNS reales (ver README).
 */
@SpringBootTest(classes = MainApplication.class, properties = {
    "dynamodb.enabled=true",
    "dynamodb.endpoint=http://localhost:65000",
    "sns.enabled=true",
    "sns.topic-arn=arn:aws:sns:us-east-1:000000000000:transfers-events"
})
class AwsWiringTest {

    @BeforeAll
    static void awsEnvironment() {
        System.setProperty("aws.region", "us-east-1");
        System.setProperty("aws.accessKeyId", "test");
        System.setProperty("aws.secretAccessKey", "test");
    }

    @Autowired
    private ITransferGateway transfers;

    @Autowired
    private IIdempotencyGateway idempotency;

    @Autowired
    private ITransferEventPublisher events;

    @Autowired
    private IRecipientTokenGateway recipientTokens;

    @Test
    void usesDynamoDbAndSnsAdaptersWhenEnabled() {
        assertThat(transfers).isInstanceOf(DynamoTransferAdapter.class);
        assertThat(idempotency).isInstanceOf(DynamoIdempotencyAdapter.class);
        assertThat(events).isInstanceOf(SnsTransferEventPublisher.class);
        assertThat(recipientTokens).isInstanceOf(DynamoRecipientTokenAdapter.class);
    }
}
