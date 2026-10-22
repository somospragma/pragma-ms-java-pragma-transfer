package co.com.pragma.transfers.model.phone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.com.pragma.transfers.model.exception.BusinessException;
import co.com.pragma.transfers.model.exception.ErrorCatalog;
import co.com.pragma.transfers.model.transfer.Money;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Modelo de pago a celular: RN-09 a RN-13 a nivel de valor. */
class PhoneModelTest {

    private static final Instant NOW = Instant.parse("2026-10-15T15:00:00Z");

    @Test
    void rn09AcceptsPeruvianMobileAndMasksIt() {
        PhoneNumber phone = PhoneNumber.parse("+51987654321");
        assertEquals("+51987654321", phone.value());
        assertEquals("+51 9** *** 321", phone.masked());
        assertEquals("+51 9** *** 321", phone.toString());
    }

    @Test
    void rn09RejectsInvalidFormatsWithPhoneInvalid() {
        for (String invalid : new String[] {"987654321", "51987654321", "+51887654321", "+5198765432",
            "+519876543210", "+52987654321", "+51 987654321"}) {
            BusinessException error = assertThrows(BusinessException.class, () -> PhoneNumber.parse(invalid));
            assertEquals(ErrorCatalog.PHONE_INVALID.code(), error.code());
            assertTrue(error.metadata().get("phone").toString().startsWith("debe cumplir"));
        }
        BusinessException missing = assertThrows(BusinessException.class, () -> PhoneNumber.parse(null));
        assertEquals("es obligatorio", missing.metadata().get("phone"));
        BusinessException blank = assertThrows(BusinessException.class, () -> PhoneNumber.parse(" "));
        assertEquals("es obligatorio", blank.metadata().get("phone"));
    }

    @Test
    void aliasMasksHolderNameAndDecidesIfPayable() {
        PhoneNumber phone = PhoneNumber.parse("+51987654321");
        RecipientAlias mario = new RecipientAlias(phone, AliasBank.PRAGBANK, "191-0007654321", "Mario Quiroz", true);
        assertEquals("Mar*** Qui***", mario.maskedName());
        assertTrue(mario.isPayable());
        assertFalse(mario.toString().contains("Mario"));
        assertFalse(mario.toString().contains("191-0007654321"));

        assertEquals("Al*** de*** Lop***",
            new RecipientAlias(phone, AliasBank.PRAGBANK, "1", "  Al de  Lopez ", true).maskedName());
        assertEquals("***", new RecipientAlias(phone, AliasBank.PRAGBANK, "1", null, true).maskedName());
        assertEquals("***", new RecipientAlias(phone, AliasBank.PRAGBANK, "1", " ", true).maskedName());

        assertFalse(new RecipientAlias(phone, AliasBank.OTHER, null, null, true).isPayable());
        assertFalse(new RecipientAlias(phone, AliasBank.PRAGBANK, "1", "Mario", false).isPayable());
        assertFalse(new RecipientAlias(phone, AliasBank.PRAGBANK, null, "Mario", true).isPayable());
        assertFalse(new RecipientAlias(phone, AliasBank.PRAGBANK, " ", "Mario", true).isPayable());
    }

    private static RecipientToken token(Instant expiresAt, Instant usedAt, String usedBy) {
        return new RecipientToken("rt_x", "CUST-000123", "191-0007654321", "+51 9** *** 321", "Mar*** Qui***",
            NOW, expiresAt, usedAt, usedBy);
    }

    @Test
    void rn12TokenIsRedeemableOnlyWhileValidUnusedAndByItsCustomer() {
        RecipientToken fresh = token(NOW.plusSeconds(300), null, null);
        assertTrue(fresh.isRedeemableBy("CUST-000123", "t1", NOW));
        assertFalse(fresh.isRedeemableBy("CUST-000999", "t1", NOW));
        assertFalse(fresh.isUsed());
        assertFalse(fresh.isExpired(NOW.plusSeconds(299)));

        // Vencido aunque siga en la tabla (el TTL de DynamoDB borra con retraso).
        assertTrue(fresh.isExpired(NOW.plusSeconds(300)));
        assertFalse(fresh.isRedeemableBy("CUST-000123", "t1", NOW.plusSeconds(301)));
        assertTrue(token(null, null, null).isExpired(NOW));

        RecipientToken used = token(NOW.plusSeconds(300), NOW, "t1");
        assertTrue(used.isUsed());
        assertFalse(used.isRedeemableBy("CUST-000123", "t2", NOW));
        assertFalse(used.isRedeemableBy("CUST-000123", null, NOW));
        // Reintento de la misma transferencia: pasa aunque ya haya vencido.
        assertTrue(used.isRedeemableBy("CUST-000123", "t1", NOW.plusSeconds(900)));
        assertFalse(token(NOW.plusSeconds(300), null, null).toString().contains("191-0007654321"));
        assertFalse(new RecipientToken("rt_x", null, "a", "m", "n", NOW, NOW, null, null)
            .isRedeemableBy("CUST-000123", "t1", NOW));
    }

    @Test
    void rn11PhonePolicyAllowsOnlyPenUpToFiveHundred() {
        PhoneTransferPolicy policy = new PhoneTransferPolicy(new BigDecimal("500.00"), "PEN", Duration.ofMinutes(5));
        assertFalse(policy.exceedsLimit(Money.parse("500.00", "PEN")));
        assertTrue(policy.exceedsLimit(Money.parse("500.01", "PEN")));
        assertTrue(policy.allowsCurrency(Money.parse("10.00", "PEN")));
        assertFalse(policy.allowsCurrency(Money.parse("10.00", "USD")));
        assertThrows(NullPointerException.class, () -> new PhoneTransferPolicy(null, "PEN", Duration.ZERO));
        assertThrows(NullPointerException.class, () -> new PhoneTransferPolicy(BigDecimal.ONE, null, Duration.ZERO));
        assertThrows(NullPointerException.class, () -> new PhoneTransferPolicy(BigDecimal.ONE, "PEN", null));
    }

    @Test
    void phoneCommandFingerprintDependsOnTheBody() {
        PhoneTransferCommand base = new PhoneTransferCommand("k", "CUST-000123", "corr", "191-0001234567", "rt_1",
            Money.parse("45.50", "PEN"), "Almuerzo");
        PhoneTransferCommand sameBodyOtherCorrelation = new PhoneTransferCommand("k", "CUST-000123", "other",
            "191-0001234567", "rt_1", Money.parse("45.50", "PEN"), "Almuerzo");
        PhoneTransferCommand otherToken = new PhoneTransferCommand("k", "CUST-000123", "corr", "191-0001234567",
            "rt_2", Money.parse("45.50", "PEN"), "Almuerzo");
        PhoneTransferCommand noDescription = new PhoneTransferCommand("k", "CUST-000123", "corr", "191-0001234567",
            "rt_1", Money.parse("45.50", "PEN"), null);
        assertEquals(base.fingerprint(), sameBodyOtherCorrelation.fingerprint());
        assertNotEquals(base.fingerprint(), otherToken.fingerprint());
        assertNotEquals(base.fingerprint(), noDescription.fingerprint());
        assertEquals(64, base.fingerprint().length());
    }
}
