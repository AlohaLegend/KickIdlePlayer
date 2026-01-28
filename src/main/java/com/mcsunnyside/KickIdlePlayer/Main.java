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
                "s, players(threshold)=" + playersThreshold + ", kick-tps=" + kickTps +
                ", kickFull=" + kickFull);

        new BukkitRunnable() {
            @Override
            public void run() {

                // Handle "full" condition using CONFIG value instead of server max
                if (pendingKickForFull) {
                    if (tryKickOneAfk(kickFullMsg)) {
                        getLogger().info("Freed a slot by kicking an AFK player (config full).");
                    }
                    pendingKickForFull = false;
                }

                // TPS-based kicking
                if (kickFull && Bukkit.getOnlinePlayers().size() < playersThreshold) {
                    return; // not enough players to start kicking
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
        if (Bukkit.getOnlinePlayers().size() < playersThreshold) {
            return;
        }
        if (essentials.getTimer().getAverageTPS() >= kickTps) {
            return;
        }
        tryKickLongAfk();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPreLogin(AsyncPlayerPreLoginEvent e) {
        // Treat CONFIG "players" as the max for testing/full logic
        if (kickFull && Bukkit.getOnlinePlayers().size() >= playersThreshold) {
            pendingKickForFull = true;
            e.setLoginResult(AsyncPlayerPreLoginEvent.Result.ALLOWED);
        }
    }
}
