package cloud.cholewa.light.emptyhouse;

/** Whether anybody of the household is at home, as presence-service says it (HAS-263). */
public enum HouseOccupancy {
    EMPTY("empty"), OCCUPIED("occupied"), UNKNOWN("unknown");

    private final String value;

    HouseOccupancy(final String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
