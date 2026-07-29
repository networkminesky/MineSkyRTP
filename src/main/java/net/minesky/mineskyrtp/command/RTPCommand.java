package net.minesky.mineskyrtp.command;

import net.minesky.mineskyrtp.config.ConfigManager;
import net.minesky.mineskyrtp.manager.RTPManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.List;

public class RTPCommand implements CommandExecutor, TabCompleter {

    private final RTPManager rtpManager;
    private final ConfigManager configManager;

    public RTPCommand(RTPManager rtpManager, ConfigManager configManager) {
        this.rtpManager = rtpManager;
        this.configManager = configManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(configManager.getMsgOnlyPlayers());
            return true;
        }

        Player player = (Player) sender;

        if (!player.hasPermission("mineskyrtp.use")) {
            player.sendMessage(configManager.getMsgNoPermission());
            return true;
        }

        rtpManager.executeRTP(player);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return Collections.emptyList();
    }
}