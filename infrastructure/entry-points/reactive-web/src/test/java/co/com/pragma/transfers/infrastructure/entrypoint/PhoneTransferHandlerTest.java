package co.com.pragma.transfers.infrastructure.entrypoint;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PhoneTransferHandlerTest {

    @Test
    void unencodedPlusInQueryStringIsRestored() {
        assertThat(PhoneTransferHandler.normalizePhone(" 51987654321")).isEqualTo("+51987654321");
        assertThat(PhoneTransferHandler.normalizePhone("+51987654321")).isEqualTo("+51987654321");
        assertThat(PhoneTransferHandler.normalizePhone("51987654321")).isEqualTo("51987654321");
    }
}
