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

    // Config
    private int maxIdleSeconds = 600;          // max-idle-time
    private int playerThreshold = 64;          // players (treat as "full threshold")
    private double tpsThreshold = 10.0;        // kick-tps

    private String tpsKickMsg = "&cYou have been AFK for too long and the server is under load.";
    private boolean kickWhenFull = true;       // kick-afking-players-when-server-is-full
    private String fullKickMsg = "&cYou were kicked because the server is full and you are AFK.";

    private Essentials essentials;

    // Async pre-login sets this; main thread processes it
    private volatile boolean pendingFullKick = false;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfigValues();

        Plugin ess = Bukkit.getPluginManager().getPlugin("Essentials");
        if (!(ess instanceof Essentials)) {
            getLogger().severe("EssentialsX is required to run KickIdlePlayer.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        essentials = (Essentials) ess;

        Bukkit.getPluginManager().registerEvents(this, this);

        getLogger().info("KickIdlePlayer enabled. max-idle-time=" + maxIdleSeconds +
                "s, players(threshold)=" + playerThreshold +
                ", kick-tps=" + tpsThreshold +
                ", kickWhenFull=" + kickWhenFull);

        // Main loop: safe + simple
        new BukkitRunnable() {
            @Override
            public void run() {
                // FULL MODE: separate from TPS mode
                if (kickWhenFull && (pendingFullKick || Bukkit.getOnlinePlayers().size() >= playerThreshold)) {
                    boolean kicked = kickOneLongestAfk(fullKickMsg);
                    pendingFullKick = false;

                    // If nobody was AFK, nothing to do.
                    if (kicked) {
                        getLogger().info("Kicked AFK player to reduce player count pressure (full mode).");
                    }
                }

                // TPS MODE: separate from full mode
                double avgTps = essentials.getTimer().getAverageTPS();
                if (avgTps < tpsThreshold) {
                    int kickedCount = kickLongAfkWhenTpsLow();
                    if (kickedCount > 0) {
                        getLogger().info("Kicked " + kickedCount + " player(s) (TPS mode). avgTPS=" + avgTps);
                    }
                }
            }
        }.runTaskTimer(this, 0L, 80L); // every 4 seconds
    }

    private void loadConfigValues() {
        maxIdleSeconds = getConfig().getInt("max-idle-time", maxIdleSeconds);
        playerThreshold = getConfig().getInt("players", playerThreshold);
        tpsThreshold = getConfig().getDouble("kick-tps", tpsThreshold);

        tpsKickMsg = ChatColor.translateAlternateColorCodes('&',
                getConfig().getString("kick-message", tpsKickMsg)
        );

        kickWhenFull = getConfig().getBoolean("kick-afking-players-when-server-is-full", kickWhenFull);

        fullKickMsg = ChatColor.translateAlternateColorCodes('&',
                getConfig().getString("kick-afking-full-message", fullKickMsg)
        );
    }

    /**
     * TPS MODE:
     * Kick any player who is AFK and AFK duration > maxIdleSeconds.
     * Returns number kicked.
     */
    private int kickLongAfkWhenTpsLow() {
        long now = System.currentTimeMillis();
        long maxAllowIdleMs = (long) maxIdleSeconds * 1000L;

        int kicked = 0;
        for (User user : essentials.getOnlineUsers()) {
            if (!user.isAfk()) continue;

            long afkSince = user.getAfkSince();
            if (afkSince <= 0) continue;

            if ((now - afkSince) > maxAllowIdleMs) {
                getLogger().info("Kicking " + user.getName() + " (TPS mode: AFK too long).");
                user.getBase().kickPlayer(tpsKickMsg);
                kicked++;
            }
        }
        return kicked;
    }

    /**
     * FULL MODE:
     * Kick ONE AFK player (the one AFK the longest).
     * Returns true if a kick occurred.
     */
    private boolean kickOneLongestAfk(String message) {
        User target = null;
        long oldestAfkSince = Long.MAX_VALUE;

        for (User user : essentials.getOnlineUsers()) {
            if (!user.isAfk()) continue;

            long afkSince = user.getAfkSince();
            if (afkSince <= 0) continue;

            if (afkSince < oldestAfkSince) {
                oldestAfkSince = afkSince;
                target = user;
            }
        }

        if (target == null) return false;

        getLogger().info("Kicking " + target.getName() + " (full mode: longest AFK).");
        target.getBase().kickPlayer(message);
        return true;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoin(PlayerJoinEvent e) {
        // FULL MODE quick reaction on join (still separate from TPS mode)
        if (kickWhenFull && Bukkit.getOnlinePlayers().size() >= playerThreshold) {
            kickOneLongestAfk(fullKickMsg);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPreLogin(AsyncPlayerPreLoginEvent e) {
        // Async: don't kick here. Just flag for main thread.
        if (kickWhenFull && Bukkit.getOnlinePlayers().size() >= playerThreshold) {
            pendingFullKick = true;
            // Allow them to attempt join; we will free a slot ASAP on main thread.
            e.setLoginResult(AsyncPlayerPreLoginEvent.Result.ALLOWED);
        }
    }
}
