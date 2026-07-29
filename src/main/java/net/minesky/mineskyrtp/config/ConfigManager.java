package net.minesky.mineskyrtp.config;

import net.minesky.mineskyrtp.MineSkyRTP;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Sound;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ConfigManager {

    private final MineSkyRTP plugin;

    private String worldName;
    private int minRadius;
    private int maxRadius;
    private int centerX;
    private int centerZ;
    private int cacheSize;
    private int generationDelaySeconds;
    private int cooldownSeconds;

    private String msgTeleported;
    private String msgCooldown;
    private String msgNoLocation;
    private String msgNoPermission;
    private String msgOnlyPlayers;
    private String msgWorldNotFound;

    private boolean soundEnabled;
    private Sound sound;
    private float soundVolume;
    private float soundPitch;

    private final Set<Material> unsafeBlocks = new HashSet<>();

    public ConfigManager(MineSkyRTP plugin) {
        this.plugin = plugin;
        reloadConfig();
    }

    public void reloadConfig() {
        plugin.reloadConfig();
        this.worldName = plugin.getConfig().getString("world", "world");
        this.minRadius = plugin.getConfig().getInt("min-radius", 500);
        this.maxRadius = plugin.getConfig().getInt("max-radius", 5000);
        this.centerX = plugin.getConfig().getInt("center-x", 0);
        this.centerZ = plugin.getConfig().getInt("center-z", 0);
        this.cacheSize = plugin.getConfig().getInt("cache-size", 5);
        this.generationDelaySeconds = plugin.getConfig().getInt("generation-delay-seconds", 3);
        this.cooldownSeconds = plugin.getConfig().getInt("cooldown-seconds", 30);

        this.msgTeleported = colorize(plugin.getConfig().getString("messages.teleported", "&aTeleported!"));
        this.msgCooldown = colorize(plugin.getConfig().getString("messages.cooldown", "&cCooldown!"));
        this.msgNoLocation = colorize(plugin.getConfig().getString("messages.no-location", "&eSearching..."));
        this.msgNoPermission = colorize(plugin.getConfig().getString("messages.no-permission", "&cNo permission."));
        this.msgOnlyPlayers = colorize(plugin.getConfig().getString("messages.only-players", "&cPlayers only."));
        this.msgWorldNotFound = colorize(plugin.getConfig().getString("messages.world-not-found", "&cWorld not found."));

        this.soundEnabled = plugin.getConfig().getBoolean("sounds.enabled", true);
        String soundStr = plugin.getConfig().getString("sounds.sound", "ENTITY_ENDERMAN_TELEPORT");
        try {
            this.sound = Sound.valueOf(soundStr);
        } catch (Exception e) {
            this.sound = Sound.ENTITY_ENDERMAN_TELEPORT;
        }
        this.soundVolume = (float) plugin.getConfig().getDouble("sounds.volume", 1.0);
        this.soundPitch = (float) plugin.getConfig().getDouble("sounds.pitch", 1.0);

        this.unsafeBlocks.clear();
        List<String> blockNames = plugin.getConfig().getStringList("unsafe-blocks");
        for (String name : blockNames) {
            try {
                Material mat = Material.valueOf(name.toUpperCase());
                this.unsafeBlocks.add(mat);
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    private String colorize(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    public String getWorldName() {
        return worldName;
    }

    public int getMinRadius() {
        return minRadius;
    }

    public int getMaxRadius() {
        return maxRadius;
    }

    public int getCenterX() {
        return centerX;
    }

    public int getCenterZ() {
        return centerZ;
    }

    public int getCacheSize() {
        return cacheSize;
    }

    public int getGenerationDelaySeconds() {
        return generationDelaySeconds;
    }

    public int getCooldownSeconds() {
        return cooldownSeconds;
    }

    public String getMsgTeleported() {
        return msgTeleported;
    }

    public String getMsgCooldown() {
        return msgCooldown;
    }

    public String getMsgNoLocation() {
        return msgNoLocation;
    }

    public String getMsgNoPermission() {
        return msgNoPermission;
    }

    public String getMsgOnlyPlayers() {
        return msgOnlyPlayers;
    }

    public String getMsgWorldNotFound() {
        return msgWorldNotFound;
    }

    public boolean isSoundEnabled() {
        return soundEnabled;
    }

    public Sound getSound() {
        return sound;
    }

    public float getSoundVolume() {
        return soundVolume;
    }

    public float getSoundPitch() {
        return soundPitch;
    }

    public Set<Material> getUnsafeBlocks() {
        return unsafeBlocks;
    }
}