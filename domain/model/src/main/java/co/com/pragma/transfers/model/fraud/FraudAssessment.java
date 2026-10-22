package co.com.pragma.transfers.model.fraud;

import java.util.List;

/** Respuesta de antifraude: puntaje 0-100 y motivos. */
public record FraudAssessment(int score, List<String> reasons) {

    public FraudAssessment {
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }

    public FraudDecision decision() {
        return FraudDecision.fromScore(score);
    }
}
