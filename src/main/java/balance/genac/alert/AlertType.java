package balance.genac.alert;

public enum AlertType {

    LOW("Low", "§a"),

    MEDIUM("Medium", "§e"),

    HIGH("High", "§c"),

    CRITICAL("Critical", "§4"),

    EXPERIMENTAL("Experimental", "§d"),

    DEBUG("Debug", "§7");

    private final String displayName;
    private final String colorCode;

    AlertType(String displayName, String colorCode) {
        this.displayName = displayName;
        this.colorCode = colorCode;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getColorCode() {
        return colorCode;
    }

    public String getColoredName() {
        return colorCode + displayName;
    }
}