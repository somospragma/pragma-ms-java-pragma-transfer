package co.com.pragma.transfers.model.fraud;

/**
 * RN-07: menor a 60 aprueba, 60 a 79 queda en revisión (también si antifraude
 * no responde a tiempo), 80 o más rechaza.
 */
public enum FraudDecision {
    APPROVE,
    REVIEW,
    REJECT;

    public static final int REVIEW_THRESHOLD = 60;
    public static final int REJECT_THRESHOLD = 80;

    public static FraudDecision fromScore(int score) {
        if (score >= REJECT_THRESHOLD) {
            return REJECT;
        }
        return score >= REVIEW_THRESHOLD ? REVIEW : APPROVE;
    }
}
