package studio.volkoff.atlas.model;

/** Discovery status of an atlas value; declaration order is severity order, so the worst is the maximum. */
public enum Status {
    STATIC, INFERRED, UNKNOWN;

    public Status worst(Status other) {
        return compareTo(other) >= 0 ? this : other;
    }
}
