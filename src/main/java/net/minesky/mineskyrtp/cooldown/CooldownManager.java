package net.minesky.mineskyrtp.cooldown;

import net.minesky.mineskyrtp.config.ConfigManager;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class CooldownManager {

    private final ConfigManager configManager;
    private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();

    public CooldownManager(ConfigManager configManager) {
        this.configManager = configManager;
    }

    public boolean hasCooldown(Player player) {
        if (player.hasPermission("mineskyrtp.bypass.cooldown")) {
            return false;
        }
        Long expireTime = cooldowns.get(player.getUniqueId());
        if (expireTime == null) {
            return false;
        }
        if (System.currentTimeMillis() >= expireTime) {
            cooldowns.remove(player.getUniqueId());
            return false;
        }
        return true;
    }

    public long getRemainingSeconds(Player player) {
        Long expireTime = cooldowns.get(player.getUniqueId());
        if (expireTime == null) {
            return 0;
        }
        long remaining = (expireTime - System.currentTimeMillis()) / 1000;
        return Math.max(0, remaining);
    }

    public void setCooldown(Player player) {
        if (player.hasPermission("mineskyrtp.bypass.cooldown")) {
            return;
        }
        long expireTime = System.currentTimeMillis() + (configManager.getCooldownSeconds() * 1000L);
        cooldowns.put(player.getUniqueId(), expireTime);
    }
}