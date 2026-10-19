package cloud.cholewa.light.occupancy;

/** What a sensor of the occupancy reports: a presence sensor whether somebody is there, a motion sensor motion. */
public enum SensorKind {
    PRESENCE("presence sensor"), MOTION("motion sensor");

    private final String value;

    SensorKind(final String value) {
        this.value = value;
    }

    /** The value of the kind in the device registry, the way the reads name it. */
    public String value() {
        return value;
    }
}
