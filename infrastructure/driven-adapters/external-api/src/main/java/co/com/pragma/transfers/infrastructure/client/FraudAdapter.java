package co.com.pragma.transfers.infrastructure.client;

import co.com.pragma.transfers.infrastructure.client.dto.FraudScoreRequestDto;
import co.com.pragma.transfers.infrastructure.client.dto.FraudScoreResponseDto;
import co.com.pragma.transfers.model.fraud.FraudAssessment;
import co.com.pragma.transfers.model.transfer.Transfer;
import co.com.pragma.transfers.ports.IFraudGateway;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Adaptador de antifraude (openapi/fraud.yaml). Timeout corto (1 s por
 * contrato) y sin reintentos: si no responde, el caso de uso deja la
 * transferencia en PENDING_REVIEW.
 */
public class FraudAdapter implements IFraudGateway {

    private final WebClient client;
    private final OutboundCalls calls;

    public FraudAdapter(WebClient fraudWebClient, OutboundCalls calls) {
        this.client = fraudWebClient;
        this.calls = calls;
    }

    @Override
    public Mono<FraudAssessment> score(Transfer transfer) {
        FraudScoreRequestDto request = new FraudScoreRequestDto(transfer.fraudOperationId(), transfer.customerId(),
            transfer.channel().name(), transfer.sourceAccountId(), transfer.targetAccountId(),
            CoreBankingAdapter.toDto(transfer.amount()));
        return calls.write(correlationId -> client.post()
                .uri("/fraud/v1/score")
                .headers(OutboundCalls.correlation(correlationId))
                .bodyValue(request)
                .exchangeToMono(response -> response.statusCode().is2xxSuccessful()
                    ? response.bodyToMono(FraudScoreResponseDto.class)
                    : response.releaseBody().then(Mono.error(calls.unavailable(response.statusCode().value())))))
            .flatMap(dto -> dto.score() == null
                ? Mono.error(calls.unavailable(new IllegalStateException("score ausente")))
                : Mono.just(new FraudAssessment(dto.score(), dto.reasons())));
    }
}
