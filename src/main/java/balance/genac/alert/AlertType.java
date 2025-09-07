package balance.genac.alert;

public enum AlertType {

    COMBAT("Combat", "§a"),

    HIGH("High", "§c"),

    MOVEMENT("Movement", "§4"),

    EXPERIMENTAL("Experimental", "§d");



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