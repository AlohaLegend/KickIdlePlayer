package com.mcsunnyside.KickIdlePlayer;

import com.earth2me.essentials.Essentials;
import com.earth2me.essentials.User;
import net.ess3.api.events.AfkStatusChangeEvent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class Main extends JavaPlugin implements Listener {

    // Config
    private int maxIdleSeconds = 600;          // max-idle-time (seconds AFTER Essentials marks AFK)
    private int playerThreshold = 64;          // players (full threshold)
    private double tpsThreshold = 10.0;        // kick-tps

    private boolean enableTpsMode = true;      // enable-tps-mode
    private boolean enableFullMode = true;     // enable-full-mode

    private String tpsKickMsg = "&cYou were kicked for being AFK while the server TPS is low.";
    private String fullKickMsg = "&cYou were kicked for being AFK while the server is full.";

    private Essentials essentials;

    // Async pre-login sets this; main thread processes it
    private volatile boolean pendingFullKick = false;

    // Single repeating task (no duplicates)
    private BukkitRunnable tickTask;

    // AFK timing we control (AFK start timestamp in millis)
    private final Map<UUID, Long> afkStartMs = new ConcurrentHashMap<>();

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

        // Seed AFK map for anyone already AFK at startup (rare but safe)
        essentials.getOnlineUsers().forEach(u -> {
            if (u.isAfk()) {
                afkStartMs.put(u.getUUID(), System.currentTimeMillis());
            }
        });

        reloadKickIdleConfig(true);
        startOrRestartTask();
    }

    @Override
    public void onDisable() {
        stopTask();
        afkStartMs.clear();
    }

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
                // FULL MODE: purely player-count based (no TPS checks)
                if (enableFullMode && Bukkit.getOnlinePlayers().size() >= playerThreshold) {
                    kickOneLongestAfk(fullKickMsg);
                }

                // FULL MODE: async prelogin signal (still independent of TPS)
                if (enableFullMode && pendingFullKick) {
                    kickOneLongestAfk(fullKickMsg);
                    pendingFullKick = false;
                }

                // TPS MODE: purely TPS based (no player-count checks)
                if (enableTpsMode) {
                    double avgTps = essentials.getTimer().getAverageTPS();
                    if (avgTps < tpsThreshold) {
                        kickLongAfkWhenTpsLow();
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
     * Kick any player whose AFK duration (tracked by AfkStatusChangeEvent) exceeds maxIdleSeconds.
     */
    private void kickLongAfkWhenTpsLow() {
        long now = System.currentTimeMillis();
        long maxAllowIdleMs = (long) maxIdleSeconds * 1000L;

        for (User user : essentials.getOnlineUsers()) {
            if (!user.isAfk()) continue;

            Long start = afkStartMs.get(user.getUUID());
            if (start == null) {
                // Fallback: if we somehow missed the event, start the clock now (prevents instant kicks)
                afkStartMs.put(user.getUUID(), now);
                continue;
            }

            if ((now - start) > maxAllowIdleMs) {
                getLogger().info("Kicking " + user.getName() + " (TPS mode: AFK too long).");
                user.getBase().kickPlayer(tpsKickMsg);
                afkStartMs.remove(user.getUUID());
            }
        }
    }

    /**
     * FULL MODE:
     * Kick ONE AFK player, chosen as the longest AFK using our tracked start time.
     */
    private boolean kickOneLongestAfk(String message) {
        User target = null;
        long oldestStart = Long.MAX_VALUE;

        for (User user : essentials.getOnlineUsers()) {
            if (!user.isAfk()) continue;

            Long start = afkStartMs.get(user.getUUID());
            if (start == null) continue; // if unknown, don't pick them (safer)

            if (start < oldestStart) {
                oldestStart = start;
                target = user;
            }
        }

        if (target == null) return false;

        getLogger().info("Kicking " + target.getName() + " (full mode: longest AFK).");
        target.getBase().kickPlayer(message);
        afkStartMs.remove(target.getUUID());
        return true;
    }

    // Essentials AFK status change: this is the "true" AFK start/stop moment.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAfkChange(AfkStatusChangeEvent e) {
        UUID uuid = e.getAffected().getUUID();
        boolean nowAfk = e.getValue(); // true = now AFK, false = no longer AFK :contentReference[oaicite:2]{index=2}

        if (nowAfk) {
            afkStartMs.put(uuid, System.currentTimeMillis());
        } else {
            afkStartMs.remove(uuid);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onQuit(PlayerQuitEvent e) {
        afkStartMs.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoin(PlayerJoinEvent e) {
        // If they join already AFK (unlikely), start clock now
        Player p = e.getPlayer();
        User u = essentials.getUser(p);
        if (u != null && u.isAfk()) {
            afkStartMs.put(p.getUniqueId(), System.currentTimeMillis());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPreLogin(AsyncPlayerPreLoginEvent e) {
        if (enableFullMode && Bukkit.getOnlinePlayers().size() >= playerThreshold) {
            pendingFullKick = true;
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
