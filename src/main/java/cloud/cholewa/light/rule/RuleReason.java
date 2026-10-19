package cloud.cholewa.light.rule;

/**
 * Why the service sent a command of its own, by the value written to the log of the runs: a rule of a room
 * (HAS-262), the empty house by day or the presence simulation by night (HAS-263).
 */
public enum RuleReason {
    AUTO_OFF("auto-off"), MOTION_ON("motion-on"), MOTION_OFF("motion-off"),
    EMPTY_HOUSE("empty-house"), PRESENCE_SIMULATION("presence-simulation");

    private final String value;

    RuleReason(final String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
