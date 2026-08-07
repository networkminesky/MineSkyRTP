package net.minesky.mineskyrtp.storage;

import net.minesky.mineskyrtp.MineSkyRTP;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

public class LocationStorage {

    private final MineSkyRTP plugin;
    private final File file;
    private YamlConfiguration config;

    public LocationStorage(MineSkyRTP plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "locations.yml");
        loadConfig();
    }

    private void loadConfig() {
        if (!file.exists()) {
            try {
                plugin.getDataFolder().mkdirs();
                file.createNewFile();
            } catch (IOException ignored) {
            }
        }
        this.config = YamlConfiguration.loadConfiguration(file);
    }

    public List<Location> loadSavedLocations() {
        List<Location> locations = new ArrayList<>();
        if (!file.exists()) return locations;

        List<String> list = config.getStringList("saved-locations");
        for (String str : list) {
            try {
                String[] parts = str.split(";");
                if (parts.length >= 6) {
                    World world = Bukkit.getWorld(parts[0]);
                    if (world != null) {
                        double x = Double.parseDouble(parts[1]);
                        double y = Double.parseDouble(parts[2]);
                        double z = Double.parseDouble(parts[3]);
                        float yaw = Float.parseFloat(parts[4]);
                        float pitch = Float.parseFloat(parts[5]);
                        locations.add(new Location(world, x, y, z, yaw, pitch));
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return locations;
    }

    public void saveLocations(Queue<Location> locations) {
        List<String> list = new ArrayList<>();
        for (Location loc : locations) {
            if (loc.getWorld() != null) {
                String str = loc.getWorld().getName() + ";" +
                        loc.getX() + ";" +
                        loc.getY() + ";" +
                        loc.getZ() + ";" +
                        loc.getYaw() + ";" +
                        loc.getPitch();
                list.add(str);
            }
        }
        config.set("saved-locations", list);
        try {
            config.save(file);
        } catch (IOException ignored) {
        }
    }
}