package cloud.cholewa.light.state;

import cloud.cholewa.home.model.LightKind;
import cloud.cholewa.light.model.LightReading;
import cloud.cholewa.shelly.model.Gen1LightStatus;
import cloud.cholewa.shelly.model.Gen2LightStatus;
import cloud.cholewa.shelly.model.Relay;
import cloud.cholewa.shelly.model.RgbwStatus;
import cloud.cholewa.shelly.model.SwitchStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LightReadingsTest {

    @Test
    void should_read_a_gen2_relay() {
        assertThat(LightReadings.ofSwitch(new SwitchStatus().output(true).source("button")))
            .contains(new LightReading(true, null, null, null, "button"));
        assertThat(LightReadings.ofSwitch(new SwitchStatus())).isEmpty();
    }

    //off, a dimmer still says the level it comes back at
    @Test
    void should_read_a_gen2_dimmer_with_its_brightness() {
        assertThat(LightReadings.ofLight(new Gen2LightStatus().output(false).brightness(80)))
            .contains(new LightReading(false, 80, null, null, null));
        assertThat(LightReadings.ofLight(new Gen2LightStatus().output(true).brightness(130)))
            .contains(new LightReading(true, null, null, null, null));
    }

    @Test
    void should_read_a_gen2_color_component() {
        assertThat(LightReadings.ofRgbw(new RgbwStatus().output(true).rgb(List.of(0, 255, 0)).brightness(100)))
            .contains(new LightReading(true, 100, List.of(0, 255, 0), null, null));
        assertThat(LightReadings.ofRgbw(new RgbwStatus().output(true).rgb(List.of(0, 300, 0)).white(40)))
            .contains(new LightReading(true, null, null, 40, null));
        assertThat(LightReadings.ofRgbw(new RgbwStatus().output(true).rgb(List.of(1, 2))))
            .contains(new LightReading(true, null, null, null, null));
    }

    @Test
    void should_read_a_gen1_relay_and_a_gen1_dimmer() {
        assertThat(LightReadings.ofRelay(new Relay().ison(true).source("input")))
            .contains(new LightReading(true, null, null, null, "input"));
        assertThat(LightReadings.ofGen1Light(new Gen1LightStatus().ison(false).brightness(30), LightKind.DIMMER))
            .contains(new LightReading(false, 30, null, null, null));
    }

    //the mode color has no brightness: its level is the gain
    @Test
    void should_read_a_gen1_light_in_the_mode_color_by_its_gain_and_channels() {
        assertThat(LightReadings.ofGen1Light(
            new Gen1LightStatus().ison(true).gain(70).red(255).green(10).blue(0).white(5).brightness(12), LightKind.RGBW))
            .contains(new LightReading(true, 70, List.of(255, 10, 0), 5, null));
    }

    @Test
    void should_read_the_status_of_an_eaton_relay() {
        assertThat(LightReadings.ofEatonStatus(1, LightKind.RELAY)).contains(LightReading.switched(true));
        assertThat(LightReadings.ofEatonStatus(0, LightKind.RELAY)).contains(LightReading.switched(false));
        assertThat(LightReadings.ofEatonStatus(2, LightKind.RELAY)).isEmpty();
        assertThat(LightReadings.ofEatonStatus(null, LightKind.RELAY)).isEmpty();
    }

    //a dimmer reports its level; 0 is off and says nothing of the level it comes back at
    @Test
    void should_read_the_status_of_an_eaton_dimmer_as_its_level() {
        assertThat(LightReadings.ofEatonStatus(80, LightKind.DIMMER)).contains(new LightReading(true, 80, null, null, null));
        assertThat(LightReadings.ofEatonStatus(0, LightKind.DIMMER)).contains(LightReading.switched(false));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 101, 255})
    void should_not_read_an_eaton_level_out_of_range(final int value) {
        assertThat(LightReadings.ofEatonStatus(value, LightKind.DIMMER)).isEmpty();
    }
}
