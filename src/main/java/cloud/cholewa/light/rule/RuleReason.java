package cloud.cholewa.light.rule;

/** Why the engine sent a command (HAS-262), by the value written to the log of the runs. */
public enum RuleReason {
    AUTO_OFF("auto-off"), MOTION_ON("motion-on"), MOTION_OFF("motion-off");

    private final String value;

    RuleReason(final String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
