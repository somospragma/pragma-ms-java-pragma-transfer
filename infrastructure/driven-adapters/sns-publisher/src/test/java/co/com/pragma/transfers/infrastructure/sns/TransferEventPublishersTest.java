package co.com.pragma.transfers.infrastructure.sns;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import co.com.pragma.transfers.model.event.TransferEvent;
import co.com.pragma.transfers.model.event.TransferEventType;
import co.com.pragma.transfers.model.transfer.Money;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.test.StepVerifier;
import software.amazon.awssdk.services.sns.SnsAsyncClient;
import software.amazon.awssdk.services.sns.model.PublishRequest;
import software.amazon.awssdk.services.sns.model.PublishResponse;

class TransferEventPublishersTest {

    private static TransferEvent event(TransferEventType type, String failureCode, String correlationId) {
        return new TransferEvent("e-1", type, "t-1", "CUST-000123", "ACCOUNT", Money.parse("150", "PEN"),
            failureCode, Instant.parse("2026-10-15T15:04:06Z"), correlationId);
    }

    @Test
    void snsPublisherSendsAsyncApiPayloadAndAttributes() {
        SnsAsyncClient sns = mock(SnsAsyncClient.class);
        when(sns.publish(any(PublishRequest.class)))
            .thenReturn(CompletableFuture.completedFuture(PublishResponse.builder().messageId("m").build()));
        SnsTransferEventPublisher publisher = new SnsTransferEventPublisher(sns, "arn:aws:sns:us-east-1:1:transfers-events");

        StepVerifier.create(publisher.publish(event(TransferEventType.TransferCompensated, "CREDIT_FAILED", "corr-1")))
            .verifyComplete();

        ArgumentCaptor<PublishRequest> request = ArgumentCaptor.forClass(PublishRequest.class);
        verify(sns).publish(request.capture());
        PublishRequest sent = request.getValue();
        assertThat(sent.topicArn()).isEqualTo("arn:aws:sns:us-east-1:1:transfers-events");
        assertThat(sent.message())
            .contains("\"eventType\":\"TransferCompensated\"")
            .contains("\"amount\":{\"value\":\"150.00\",\"currency\":\"PEN\"}")
            .contains("\"failureCode\":\"CREDIT_FAILED\"")
            .contains("\"correlationId\":\"corr-1\"");
        assertThat(sent.messageAttributes().get("eventType").stringValue()).isEqualTo("TransferCompensated");
        assertThat(sent.messageAttributes().get("correlationId").stringValue()).isEqualTo("corr-1");
    }

    @Test
    void snsPublisherOmitsEmptyOptionalFieldsAndPropagatesErrors() {
        SnsAsyncClient sns = mock(SnsAsyncClient.class);
        when(sns.publish(any(PublishRequest.class)))
            .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("sns down")));
        SnsTransferEventPublisher publisher = new SnsTransferEventPublisher(sns, "arn");

        StepVerifier.create(publisher.publish(event(TransferEventType.TransferCompleted, null, "")))
            .expectError(IllegalStateException.class)
            .verify();
        ArgumentCaptor<PublishRequest> request = ArgumentCaptor.forClass(PublishRequest.class);
        verify(sns).publish(request.capture());
        assertThat(request.getValue().message()).doesNotContain("failureCode");
        assertThat(request.getValue().messageAttributes()).doesNotContainKey("correlationId");
        assertThat(SnsTransferEventPublisher.payload(event(TransferEventType.TransferCompleted, null, null)))
            .containsEntry("correlationId", null);
    }

    @Test
    void inMemoryPublisherKeepsABoundedHistory() {
        InMemoryTransferEventPublisher publisher = new InMemoryTransferEventPublisher();
        for (int i = 0; i < InMemoryTransferEventPublisher.CAPACITY + 3; i++) {
            publisher.publish(event(TransferEventType.TransferCompleted, null, "c")).block();
        }
        assertThat(publisher.published()).hasSize(InMemoryTransferEventPublisher.CAPACITY);
        publisher.clear();
        assertThat(publisher.published()).isEmpty();
    }
}
