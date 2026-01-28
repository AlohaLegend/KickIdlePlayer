package com.mcsunnyside.KickIdlePlayer;

import com.earth2me.essentials.Essentials;
import com.earth2me.essentials.User;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

public class Main extends JavaPlugin implements Listener {

    private int maxIdleSeconds = 600;
    private int playersThreshold = 8;

    private Essentials essentials;

    private String kickMsg = "&cYou have been AFK for too long and the server is under load.";
    private double kickTps = 18.5;

    private boolean kickFull = true;
    private String kickFullMsg = "&cYou were kicked because the server is full and you are AFK.";

    // Set to true when we detect a login being blocked by "server full" and we want to free a slot.
    private volatile boolean pendingKickForFull = false;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        maxIdleSeconds = getConfig().getInt("max-idle-time", maxIdleSeconds);
        playersThreshold = getConfig().getInt("players", playersThreshold);
        kickTps = getConfig().getDouble("kick-tps", kickTps);

        kickMsg = ChatColor.translateAlternateColorCodes('&',
                getConfig().getString("kick-message", kickMsg)
        );

        kickFull = getConfig().getBoolean("kick-afking-players-when-server-is-full", kickFull);
        kickFullMsg = ChatColor.translateAlternateColorCodes('&',
                getConfig().getString("kick-afking-full-message", kickFullMsg)
        );

        Plugin ess = Bukkit.getPluginManager().getPlugin("Essentials");
        if (!(ess instanceof Essentials)) {
            getLogger().severe("EssentialsX is required to run KickIdlePlayer.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        essentials = (Essentials) ess;

        Bukkit.getPluginManager().registerEvents(this, this);

        getLogger().info("KickIdlePlayer enabled. max-idle-time=" + maxIdleSeconds +
                "s, players=" + playersThreshold + ", kick-tps=" + kickTps +
                ", kickFull=" + kickFull);

        // Run sync. Safe + simple.
        new BukkitRunnable() {
            @Override
            public void run() {
                // If we flagged a "server full" situation, try to free a slot first.
                if (pendingKickForFull) {
                    if (tryKickOneAfk(kickFullMsg)) {
                        getLogger().info("Freed a slot by kicking an AFK player (server full).");
                    }
                    pendingKickForFull = false;
                }

                // Normal TPS-based kicking
                if (kickFull && Bukkit.getOnlinePlayers().size() <= playersThreshold) {
                    return; // Not enough players to start kicking (unless server-full logic triggered)
                }
                if (essentials.getTimer().getAverageTPS() >= kickTps) {
                    return; // TPS is fine
                }

                tryKickLongAfk();
            }
        }.runTaskTimer(this, 0L, 80L); // every 4 seconds
    }

    private void tryKickLongAfk() {
        long now = System.currentTimeMillis();
        long maxAllowIdleMs = (long) maxIdleSeconds * 1000L;

        for (User user : essentials.getOnlineUsers()) {
            if (!user.isAfk()) continue;

            long afkSince = user.getAfkSince();
            if (afkSince <= 0) continue;

            if ((now - afkSince) > maxAllowIdleMs) {
                getLogger().info("Kicking player " + user.getName() + " (AFK too long while TPS is low).");
                user.getBase().kickPlayer(kickMsg);
            }
        }
    }

    private boolean tryKickOneAfk(String message) {
        if (!kickFull) return false;

        for (User user : essentials.getOnlineUsers()) {
            if (user.isAfk()) {
                user.getBase().kickPlayer(message);
                return true;
            }
        }
        return false;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoin(PlayerJoinEvent e) {
        // If server is under load right now, immediately check for long-AFK players.
        if (kickFull && Bukkit.getOnlinePlayers().size() <= playersThreshold) {
            return;
        }
        if (essentials.getTimer().getAverageTPS() >= kickTps) {
            return;
        }
        tryKickLongAfk();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPreLogin(AsyncPlayerPreLoginEvent e) {
        // This event is async — do NOT kick here.
        // If the login is being rejected because the server is full, set a flag so the next sync tick frees a slot.
        if (e.getLoginResult() == AsyncPlayerPreLoginEvent.Result.KICK_FULL && kickFull) {
            pendingKickForFull = true;
            // Let them try to join. The slot will be freed on the main thread ASAP.
            e.setLoginResult(AsyncPlayerPreLoginEvent.Result.ALLOWED);
        }
    }
}
