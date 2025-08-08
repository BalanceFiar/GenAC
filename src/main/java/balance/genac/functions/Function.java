package balance.genac.functions;

import org.bukkit.entity.Player;
import org.bukkit.event.Listener;

public interface Function extends Listener {

    String getName();
    
    String getDescription();
    
    boolean isEnabled();
    
    void clearPlayerData(Player player);
}
