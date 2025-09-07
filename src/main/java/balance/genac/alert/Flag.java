package balance.genac.alert;

import balance.genac.GenAC;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class Flag implements Listener {

    private final GenAC plugin;
    private final Map<UUID, FreezeData> frozen = new ConcurrentHashMap<>();
    private static final long FREEZE_DURATION_MS = 1000L;

    private static class FreezeData {
        final Location loc;
        volatile long endsAt;
        FreezeData(Location loc, long endsAt) {
            this.loc = loc.clone();
            this.endsAt = endsAt;
        }
    }

    public Flag(GenAC plugin) {
        this.plugin = plugin;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    public void handleAlert(Alert alert) {
        if (alert == null) return;
        if (alert.getType() != AlertType.MOVEMENT) return;
        Player p = alert.getPlayer();
        if (p == null || !p.isOnline()) return;

        
        freezeFor(p, FREEZE_DURATION_MS);
    }

    public void freezeFor(Player player, long millis) {
        UUID id = player.getUniqueId();
        long end = System.currentTimeMillis() + Math.max(0L, millis);
        frozen.compute(id, (k, v) -> v == null ? new FreezeData(player.getLocation(), end) : updateEnd(v, end));

    }

    private FreezeData updateEnd(FreezeData data, long newEnd) {
        data.endsAt = newEnd;
        return data;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        FreezeData fd = frozen.get(p.getUniqueId());
        if (fd == null) return;

        long now = System.currentTimeMillis();
        if (now >= fd.endsAt) {
            frozen.remove(p.getUniqueId());
            return;
        }

        if (e.getTo() == null) return;
        Location to = e.getTo();
        Location lock = fd.loc.clone();
        lock.setYaw(to.getYaw());
        lock.setPitch(to.getPitch());
        e.setTo(lock);
        
        if (now % 100 < 50) {
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        frozen.remove(e.getPlayer().getUniqueId());
    }
}