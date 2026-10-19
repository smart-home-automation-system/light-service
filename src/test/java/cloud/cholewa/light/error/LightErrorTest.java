package cloud.cholewa.light.error;

import cloud.cholewa.commons.error.model.ErrorId;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class LightErrorTest {

    //the codes are wire contract: the dashboard words them, a renamed constant still compiles
    @Test
    void should_keep_the_codes_callers_branch_on() {
        assertThat(Arrays.stream(LightError.values()).map(ErrorId::codeOf))
            .containsExactly("NOT_FOUND_LIGHT", "UNSUPPORTED_CAPABILITY", "DEVICE_FAILED", "REGISTRY_UNAVAILABLE",
                "INVALID_RULE", "NOT_FOUND_RULE", "INVALID_PLAN");
    }
}
