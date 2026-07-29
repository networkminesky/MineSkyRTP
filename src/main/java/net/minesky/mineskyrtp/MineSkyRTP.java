package net.minesky.mineskyrtp;

import net.minesky.mineskyrtp.command.RTPCommand;
import net.minesky.mineskyrtp.config.ConfigManager;
import net.minesky.mineskyrtp.cooldown.CooldownManager;
import net.minesky.mineskyrtp.manager.RTPManager;
import org.bukkit.plugin.java.JavaPlugin;

public final class MineSkyRTP extends JavaPlugin {

    private ConfigManager configManager;
    private CooldownManager cooldownManager;
    private RTPManager rtpManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.configManager = new ConfigManager(this);
        this.cooldownManager = new CooldownManager(configManager);
        this.rtpManager = new RTPManager(this, configManager, cooldownManager);

        this.rtpManager.startTask();

        RTPCommand commandExecutor = new RTPCommand(this.rtpManager, this.configManager);
        if (getCommand("rtp") != null) {
            getCommand("rtp").setExecutor(commandExecutor);
            getCommand("rtp").setTabCompleter(commandExecutor);
        }
    }

    @Override
    public void onDisable() {
        if (this.rtpManager != null) {
            this.rtpManager.stopTask();
        }
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public CooldownManager getCooldownManager() {
        return cooldownManager;
    }

    public RTPManager getRtpManager() {
        return rtpManager;
    }
}