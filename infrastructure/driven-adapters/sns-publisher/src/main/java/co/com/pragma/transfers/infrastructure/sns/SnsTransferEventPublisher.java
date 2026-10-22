package co.com.pragma.transfers.infrastructure.sns;

import co.com.pragma.transfers.model.event.TransferEvent;
import co.com.pragma.transfers.ports.ITransferEventPublisher;
import java.util.LinkedHashMap;
import java.util.Map;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.sns.SnsAsyncClient;
import software.amazon.awssdk.services.sns.model.MessageAttributeValue;
import software.amazon.awssdk.services.sns.model.PublishRequest;
import tools.jackson.databind.json.JsonMapper;

/**
 * Publica los eventos de transfers-events.asyncapi.yaml en SNS. El payload es
 * TransferEvent en JSON; {@code eventType} y {@code correlationId} viajan además
 * como atributos del mensaje (filtros de suscripción y trazabilidad).
 */
public class SnsTransferEventPublisher implements ITransferEventPublisher {

    static final String STRING = "String";

    private final SnsAsyncClient sns;
    private final String topicArn;
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    public SnsTransferEventPublisher(SnsAsyncClient sns, String topicArn) {
        this.sns = sns;
        this.topicArn = topicArn;
    }

    @Override
    public Mono<Void> publish(TransferEvent event) {
        return Mono.fromCallable(() -> PublishRequest.builder()
                .topicArn(topicArn)
                .message(jsonMapper.writeValueAsString(payload(event)))
                .messageAttributes(attributes(event))
                .build())
            .flatMap(request -> Mono.fromFuture(() -> sns.publish(request)))
            .then();
    }

    static Map<String, Object> payload(TransferEvent event) {
        Map<String, Object> amount = new LinkedHashMap<>();
        amount.put("value", event.amount().asString());
        amount.put("currency", event.amount().currency());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("eventId", event.eventId());
        body.put("eventType", event.eventType().name());
        body.put("transferId", event.transferId());
        body.put("customerId", event.customerId());
        body.put("channel", event.channel());
        body.put("amount", amount);
        if (event.failureCode() != null) {
            body.put("failureCode", event.failureCode());
        }
        body.put("occurredAt", event.occurredAt().toString());
        body.put("correlationId", event.correlationId());
        return body;
    }

    private static Map<String, MessageAttributeValue> attributes(TransferEvent event) {
        Map<String, MessageAttributeValue> attributes = new LinkedHashMap<>();
        attributes.put("eventType", string(event.eventType().name()));
        if (event.correlationId() != null && !event.correlationId().isBlank()) {
            attributes.put("correlationId", string(event.correlationId()));
        }
        return attributes;
    }

    private static MessageAttributeValue string(String value) {
        return MessageAttributeValue.builder().dataType(STRING).stringValue(value).build();
    }
}
