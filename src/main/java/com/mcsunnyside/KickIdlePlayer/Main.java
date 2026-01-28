package com.mcsunnyside.KickIdlePlayer;

import com.earth2me.essentials.Essentials;
import com.earth2me.essentials.User;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
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

    private boolean enableTpsMode = true;      // enable-tps-mode
    private boolean enableFullMode = true;     // enable-full-mode

    private String tpsKickMsg = "&cYou were kicked for being AFK while the server TPS is low.";
    private String fullKickMsg = "&cYou were kicked for being AFK while the server is full.";

    private Essentials essentials;

    // Async pre-login sets this; main thread processes it
    private volatile boolean pendingFullKick = false;

    // We keep one repeating task and never create duplicates
    private BukkitRunnable tickTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        Plugin ess = Bukkit.getPluginManager().getPlugin("Essentials");
        if (!(ess instanceof Essentials)) {
            getLogger().severe("EssentialsX is required to run KickIdlePlayer.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        essentials = (Essentials) ess;

        Bukkit.getPluginManager().registerEvents(this, this);

        reloadKickIdleConfig(true);
        startOrRestartTask();
    }

    @Override
    public void onDisable() {
        stopTask();
    }

    /**
     * Safe reload from config.yml into memory.
     * @param log whether to log the loaded settings
     */
    private void reloadKickIdleConfig(boolean log) {
        reloadConfig();

        maxIdleSeconds = getConfig().getInt("max-idle-time", maxIdleSeconds);
        playerThreshold = getConfig().getInt("players", playerThreshold);
        tpsThreshold = getConfig().getDouble("kick-tps", tpsThreshold);

        enableTpsMode = getConfig().getBoolean("enable-tps-mode", true);
        enableFullMode = getConfig().getBoolean("enable-full-mode", true);

        tpsKickMsg = ChatColor.translateAlternateColorCodes('&',
                getConfig().getString("kick-message", tpsKickMsg)
        );

        fullKickMsg = ChatColor.translateAlternateColorCodes('&',
                getConfig().getString("kick-afking-full-message", fullKickMsg)
        );

        if (log) {
            getLogger().info("KickIdlePlayer settings loaded: " +
                    "enableTpsMode=" + enableTpsMode +
                    ", enableFullMode=" + enableFullMode +
                    ", max-idle-time=" + maxIdleSeconds +
                    "s, players(threshold)=" + playerThreshold +
                    ", kick-tps=" + tpsThreshold);
        }
    }

    private void startOrRestartTask() {
        stopTask();

        tickTask = new BukkitRunnable() {
            @Override
            public void run() {
                // FULL MODE: separate from TPS mode
                if (enableFullMode && Bukkit.getOnlinePlayers().size() >= playerThreshold) {
                    boolean kicked = kickOneLongestAfk(fullKickMsg);
                    if (kicked) {
                        getLogger().info("Kicked AFK player (full mode).");
                    }
                }

                // FULL MODE: respond to async prelogin flag (still separate from TPS)
                if (enableFullMode && pendingFullKick) {
                    boolean kicked = kickOneLongestAfk(fullKickMsg);
                    pendingFullKick = false;
                    if (kicked) {
                        getLogger().info("Freed a slot by kicking AFK player (full mode / prelogin).");
                    }
                }

                // TPS MODE: separate from full mode
                if (enableTpsMode) {
                    double avgTps = essentials.getTimer().getAverageTPS();
                    if (avgTps < tpsThreshold) {
                        int kickedCount = kickLongAfkWhenTpsLow();
                        if (kickedCount > 0) {
                            getLogger().info("Kicked " + kickedCount + " player(s) (TPS mode). avgTPS=" + avgTps);
                        }
                    }
                }
            }
        };

        tickTask.runTaskTimer(this, 0L, 80L); // every 4 seconds
    }

    private void stopTask() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
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

            // Smaller timestamp = earlier = longer AFK
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
        // Quick reaction on join for FULL mode only (never checks TPS here)
        if (enableFullMode && Bukkit.getOnlinePlayers().size() >= playerThreshold) {
            kickOneLongestAfk(fullKickMsg);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPreLogin(AsyncPlayerPreLoginEvent e) {
        // Async: don't kick here. Just flag for main thread.
        if (enableFullMode && Bukkit.getOnlinePlayers().size() >= playerThreshold) {
            pendingFullKick = true;
            // Allow them to attempt join; we will free a slot ASAP on main thread.
            e.setLoginResult(AsyncPlayerPreLoginEvent.Result.ALLOWED);
        }
    }

    // /kickidle reload
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("kickidle")) return false;

        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("kickidle.reload")) {
                sender.sendMessage(ChatColor.RED + "No permission.");
                return true;
            }
            reloadKickIdleConfig(false);
            startOrRestartTask();
            sender.sendMessage(ChatColor.GREEN + "KickIdlePlayer reloaded.");
            return true;
        }

        sender.sendMessage(ChatColor.YELLOW + "Usage: /kickidle reload");
        return true;
    }
}
