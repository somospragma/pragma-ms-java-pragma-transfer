package co.com.pragma.transfers.infrastructure.client;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

/**
 * WebClients del core bancario, antifraude y directorio de alias con timeouts de transporte
 * (ultima linea de defensa, RES-O-001) y pool de conexiones propio por
 * dependencia (PERF-O-003). La llave viaja en X-Api-Key y llega por variable
 * de entorno desde Secrets Manager; nunca se escribe en el repo.
 */
@Configuration
public class ExternalApiWebClientConfig {

    private static final String API_KEY_HEADER = "X-Api-Key";

    @Bean
    public WebClient coreBankingWebClient(
            @Value("${core-banking.base-url}") String baseUrl,
            @Value("${core-banking.api-key:}") String apiKey) {
        return build("core-banking", baseUrl, apiKey);
    }

    @Bean
    public WebClient fraudWebClient(
            @Value("${fraud.base-url}") String baseUrl,
            @Value("${fraud.api-key:}") String apiKey) {
        return build("fraud", baseUrl, apiKey);
    }

    @Bean
    public WebClient directoryWebClient(
            @Value("${directory.base-url}") String baseUrl,
            @Value("${directory.api-key:}") String apiKey) {
        return build("directory", baseUrl, apiKey);
    }

    private WebClient build(String name, String baseUrl, String apiKey) {
        ConnectionProvider provider = ConnectionProvider.builder(name)
            .maxConnections(50)
            .pendingAcquireTimeout(Duration.ofSeconds(5))
            .build();

        HttpClient httpClient = HttpClient.create(provider)
            .responseTimeout(Duration.ofSeconds(3))
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 2000)
            .doOnConnected(conn -> conn.addHandlerLast(new ReadTimeoutHandler(3, TimeUnit.SECONDS)));

        return WebClient.builder()
            .baseUrl(baseUrl)
            .defaultHeader(API_KEY_HEADER, apiKey)
            .clientConnector(new ReactorClientHttpConnector(httpClient))
            .build();
    }
}
