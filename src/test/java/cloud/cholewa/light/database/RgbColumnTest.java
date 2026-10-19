package cloud.cholewa.light.database;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RgbColumnTest {

    @Test
    void should_write_rgb_as_text_and_read_it_back() {
        assertThat(RgbColumn.text(List.of(255, 128, 0))).isEqualTo("255,128,0");
        assertThat(RgbColumn.values("255,128,0")).containsExactly(255, 128, 0);
        assertThat(RgbColumn.values(null)).isNull();
        assertThat(RgbColumn.text(null)).isNull();
    }
}
