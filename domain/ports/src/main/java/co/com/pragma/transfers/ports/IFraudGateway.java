package co.com.pragma.transfers.ports;

import co.com.pragma.transfers.model.fraud.FraudAssessment;
import co.com.pragma.transfers.model.transfer.Transfer;
import reactor.core.publisher.Mono;

/** Antifraude: puntaje de riesgo por operación (RN-07). */
public interface IFraudGateway {

    /** Emite error si no responde dentro del timeout configurado. */
    Mono<FraudAssessment> score(Transfer transfer);
}
