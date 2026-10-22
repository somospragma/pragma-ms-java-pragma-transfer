package co.com.pragma.transfers.infrastructure.helpers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class CorrelationWebFilterTest {

    private final CorrelationWebFilter filter = new CorrelationWebFilter();

    @Test
    void prefersCorrelationIdThenRequestIdThenGenerates() {
        assertEquals("abc-1", CorrelationWebFilter.resolve("abc-1", "req-2"));
        assertEquals("req-2", CorrelationWebFilter.resolve(null, "req-2"));
        assertEquals("req-2", CorrelationWebFilter.resolve("bad value\n", "req-2"));
        String generated = CorrelationWebFilter.resolve(null, null);
        assertEquals(generated, UUID.fromString(generated).toString());
    }

    @Test
    void propagatesToHeadersAttributesAndContext() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/x")
            .header(CorrelationWebFilter.CORRELATION_HEADER, "corr-9").build());

        StepVerifier.create(filter.filter(exchange, ex -> Mono.deferContextual(ctx -> {
                assertEquals("corr-9", ctx.get(CorrelationWebFilter.TRACE_ID));
                return Mono.empty();
            })))
            .verifyComplete();

        assertEquals("corr-9", exchange.getResponse().getHeaders().getFirst(CorrelationWebFilter.CORRELATION_HEADER));
        assertEquals("corr-9", exchange.getResponse().getHeaders().getFirst(CorrelationWebFilter.HEADER));
        assertEquals("corr-9", exchange.getAttribute(CorrelationWebFilter.TRACE_ID));
    }

    @Test
    void generatesWhenMissing() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/x").build());
        StepVerifier.create(filter.filter(exchange, ex -> Mono.empty())).verifyComplete();
        assertNotNull(exchange.getAttribute(CorrelationWebFilter.TRACE_ID));
    }
}
