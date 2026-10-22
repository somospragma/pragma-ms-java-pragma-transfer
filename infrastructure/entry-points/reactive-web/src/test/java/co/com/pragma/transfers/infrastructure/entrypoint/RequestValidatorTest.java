package co.com.pragma.transfers.infrastructure.entrypoint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.com.pragma.transfers.model.exception.BusinessException;
import co.com.pragma.transfers.model.exception.ErrorCatalog;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class RequestValidatorTest {

    record SampleRequest(@NotBlank String accountId, @Positive int amount) {
    }

    private static ValidatorFactory factory;
    private static RequestValidator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = new RequestValidator(factory.getValidator());
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    @Test
    void returnsTargetWhenValid() {
        SampleRequest request = new SampleRequest("191-0001234567", 150);
        assertSame(request, validator.validate(request));
    }

    @Test
    void throwsValidationErrorWithFieldDetail() {
        BusinessException ex = assertThrows(BusinessException.class,
            () -> validator.validate(new SampleRequest("", 0)));
        assertEquals(ErrorCatalog.VALIDATION_ERROR.code(), ex.code());
        assertTrue(ex.metadata().containsKey("accountId"));
        assertTrue(ex.metadata().containsKey("amount"));
    }
}
