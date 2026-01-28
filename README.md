# KickIdlePlayer

KickIdlePlayer is a lightweight Minecraft plugin that kicks AFK players when:

• Server TPS drops below a configurable value  
• The server reaches a configurable player count  

It uses EssentialsX AFK detection and always kicks the player who has been AFK the longest first.

---

## Features

✔ TPS-based AFK kicking (performance protection)  
✔ Player-count-based AFK kicking (slot management)  
✔ Longest-AFK-first logic  
✔ One kick at a time (no mass kicks)  
✔ Safe reload command  
✔ Very low server overhead  
✔ Designed for Paper / Spigot servers  

---

## Requirements

• Minecraft server (Paper or Spigot)  
• EssentialsX (required for AFK detection)  
• Java 21+

---

## Installation

1. Download the latest JAR from the releases or build artifacts  
2. Place the JAR in your server’s plugins folder  
3. Install EssentialsX  
4. Start the server  
5. Configure config.yml  
6. Restart or run /kickidle reload

---

## Configuration

FULL MODE (player count based)

players: 64  
enable-full-mode: true  
kick-afking-full-message: "&cYou were kicked because the server is full and you are AFK."

TPS MODE (performance based)

kick-tps: 10.0  
enable-tps-mode: true  
kick-message: "&cYou were kicked for being AFK while the server TPS is low."

AFK TIME

max-idle-time: 600  

---

## Mode behavior

FULL MODE  
Kicks AFK players when:

online players ≥ players

TPS MODE  
Kicks AFK players when:

average TPS < kick-tps  
AND  
AFK time > max-idle-time  

These two modes are completely independent.

---

## Commands

/kickidle reload  
Reloads the plugin configuration safely.

Permission:  
kickidle.reload

---

## How AFK is detected

This plugin relies on EssentialsX AFK detection.  
AFK time is counted from the moment EssentialsX marks a player as AFK.

---

## Performance

KickIdlePlayer runs once every 4 seconds and only:

• checks TPS  
• checks AFK players  
• kicks at most ONE player per cycle  

It does not store Player objects or worlds and does not cause memory leaks.

---

## Authors

• Liam (modernized and maintained version)  
• Ghost-chu (original plugin)

---

## License

GPL-3.0  
You are free to modify and redistribute this plugin under the same license.

---

## Use Case

This plugin is designed for servers that want to:

• Protect TPS during lag  
• Free slots when the server is full  
• Remove long-term AFK players safely  
• Avoid mass kicking  

Perfect for survival servers, SMPs, and high player count servers.
