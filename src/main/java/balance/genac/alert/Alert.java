package balance.genac.alert;

import org.bukkit.entity.Player;

public class Alert {

    private final Player player;
    private final String checkName;
    private final AlertType type;
    private final int violationLevel;
    private final String details;
    private final long timestamp;

    public Alert(Player player, String checkName, AlertType type, int violationLevel, String details) {
        this.player = player;
        this.checkName = checkName;
        this.type = type;
        this.violationLevel = violationLevel;
        this.details = details;
        this.timestamp = System.currentTimeMillis();
    }

    public Player getPlayer() {
        return player;
    }

    public String getCheckName() {
        return checkName;
    }

    public AlertType getType() {
        return type;
    }

    public AlertType getAlertType() {
        return type;
    }

    public int getViolationLevel() {
        return violationLevel;
    }

    public String getDetails() {
        return details;
    }

    public String getMessage() {
        return details;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public String getFormattedMessage() {
        return String.format("§8[§bGenAC§8] %s§f %s §8failed §f%s §8(§f%d§8) §8- §f%s",
                type.getColoredName(),
                player.getName(),
                checkName,
                violationLevel,
                details);
    }

    @Override
    public String toString() {
        return "Alert{" +
                "player=" + player.getName() +
                ", checkName='" + checkName + '\'' +
                ", type=" + type +
                ", violationLevel=" + violationLevel +
                ", details='" + details + '\'' +
                ", timestamp=" + timestamp +
                '}';
    }
}