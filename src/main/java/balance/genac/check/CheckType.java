package balance.genac.check;

public enum CheckType {

    COMBAT("Combat", "Checks related to combat"),

    MOVEMENT("Movement", "Checks related to player movement"),

    INTERACT("Interact", "Checks related to player interactions"),

    INVENTORY("Inventory", "Checks related to inventory manipulation"),

    PACKET("Packet", "Checks related to packet analysis"),

    WORLD("World", "Checks related to world interactions"),

    CHAT("Chat", "Checks related to chat and messaging"),

    MISC("Miscellaneous", "Miscellaneous checks");

    private final String displayName;
    private final String description;

    CheckType(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }

    @Override
    public String toString() {
        return displayName;
    }
}