package co.com.pragma.transfers.infrastructure.sns;

import co.com.pragma.transfers.ports.ITransferEventPublisher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sns.SnsAsyncClient;

/** Wiring del cliente SNS async y del publicador de eventos. Solo activo con sns.enabled=true. */
@Configuration
@ConditionalOnProperty(prefix = "sns", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(SnsProperties.class)
public class SnsConfig {

    @Bean(destroyMethod = "close")
    public SnsAsyncClient snsAsyncClient(SnsProperties props) {
        var builder = SnsAsyncClient.builder();
        if (props.region() != null && !props.region().isBlank()) {
            builder.region(Region.of(props.region()));
        }
        return builder.build();
    }

    @Bean
    public ITransferEventPublisher snsTransferEventPublisher(SnsAsyncClient snsAsyncClient, SnsProperties props) {
        if (props.topicArn() == null || props.topicArn().isBlank()) {
            throw new IllegalStateException("sns.topic-arn es obligatorio con sns.enabled=true");
        }
        return new SnsTransferEventPublisher(snsAsyncClient, props.topicArn());
    }
}
