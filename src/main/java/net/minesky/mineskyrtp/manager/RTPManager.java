package net.minesky.mineskyrtp.manager;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.minesky.mineskyrtp.MineSkyRTP;
import net.minesky.mineskyrtp.config.ConfigManager;
import net.minesky.mineskyrtp.cooldown.CooldownManager;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.Queue;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class RTPManager {

    private final MineSkyRTP plugin;
    private final ConfigManager config;
    private final CooldownManager cooldownManager;
    private final Queue<Location> cache = new ConcurrentLinkedQueue<>();
    private final Random random = new Random();
    private final AtomicBoolean isGenerating = new AtomicBoolean(false);
    private ScheduledTask asyncTask;

    public RTPManager(MineSkyRTP plugin, ConfigManager config, CooldownManager cooldownManager) {
        this.plugin = plugin;
        this.config = config;
        this.cooldownManager = cooldownManager;
    }

    public void startTask() {
        long delay = Math.max(1, config.getGenerationDelaySeconds());
        this.asyncTask = plugin.getServer().getAsyncScheduler().runAtFixedRate(
                plugin,
                task -> fillCache(),
                1,
                delay,
                TimeUnit.SECONDS
        );
    }

    public void stopTask() {
        if (asyncTask != null) {
            asyncTask.cancel();
        }
        cache.clear();
    }

    private void fillCache() {
        if (cache.size() >= config.getCacheSize() || !isGenerating.compareAndSet(false, true)) {
            return;
        }

        World world = Bukkit.getWorld(config.getWorldName());
        if (world == null) {
            isGenerating.set(false);
            return;
        }

        findLocationAsync(world);
    }

    private void findLocationAsync(World world) {
        int range = config.getMaxRadius() - config.getMinRadius();
        int xOffset = config.getMinRadius() + (range > 0 ? random.nextInt(range) : 0);
        int zOffset = config.getMinRadius() + (range > 0 ? random.nextInt(range) : 0);

        if (random.nextBoolean()) xOffset = -xOffset;
        if (random.nextBoolean()) zOffset = -zOffset;

        int targetX = config.getCenterX() + xOffset;
        int targetZ = config.getCenterZ() + zOffset;

        world.getChunkAtAsync(targetX >> 4, targetZ >> 4).thenAccept(chunk -> {
            try {
                Location loc = findSafeLocationInChunk(world, chunk, targetX, targetZ);
                if (loc != null) {
                    cache.add(loc);
                }
            } finally {
                isGenerating.set(false);
            }
        }).exceptionally(ex -> {
            isGenerating.set(false);
            return null;
        });
    }

    private Location findSafeLocationInChunk(World world, Chunk chunk, int x, int z) {
        int blockX = x & 15;
        int blockZ = z & 15;

        int startY = (world.getEnvironment() == World.Environment.NETHER) ? 120 : world.getMaxHeight() - 2;
        int minY = world.getMinHeight();

        for (int y = startY; y >= minY; y--) {
            Block ground = chunk.getBlock(blockX, y, blockZ);
            if (ground.getType().isSolid()) {
                if (y + 2 < world.getMaxHeight()) {
                    Block feet = chunk.getBlock(blockX, y + 1, blockZ);
                    Block head = chunk.getBlock(blockX, y + 2, blockZ);

                    if (isSafe(ground, feet, head)) {
                        return new Location(world, x + 0.5, y + 1.0, z + 0.5);
                    }
                }
            }
        }
        return null;
    }

    private boolean isSafe(Block ground, Block feet, Block head) {
        Material gMat = ground.getType();
        Material fMat = feet.getType();
        Material hMat = head.getType();

        if (config.getUnsafeBlocks().contains(gMat) ||
                config.getUnsafeBlocks().contains(fMat) ||
                config.getUnsafeBlocks().contains(hMat)) {
            return false;
        }

        if (gMat == Material.AIR || !gMat.isSolid()) {
            return false;
        }

        boolean feetPassable = fMat.isAir() || feet.isPassable();
        boolean headPassable = hMat.isAir() || head.isPassable();

        return feetPassable && !feet.isLiquid() && headPassable && !head.isLiquid();
    }

    public void executeRTP(Player player) {
        if (cooldownManager.hasCooldown(player)) {
            long remaining = cooldownManager.getRemainingSeconds(player);
            player.sendMessage(config.getMsgCooldown().replace("%time%", String.valueOf(remaining)));
            return;
        }

        Location cachedLocation = cache.poll();
        if (cachedLocation != null) {
            performTeleport(player, cachedLocation);
            fillCache();
        } else {
            player.sendMessage(config.getMsgNoLocation());
            World world = Bukkit.getWorld(config.getWorldName());
            if (world == null) {
                player.sendMessage(config.getMsgWorldNotFound());
                return;
            }
            generateAndTeleportDirectly(player, world);
        }
    }

    private void generateAndTeleportDirectly(Player player, World world) {
        int range = config.getMaxRadius() - config.getMinRadius();
        int xOffset = config.getMinRadius() + (range > 0 ? random.nextInt(range) : 0);
        int zOffset = config.getMinRadius() + (range > 0 ? random.nextInt(range) : 0);

        if (random.nextBoolean()) xOffset = -xOffset;
        if (random.nextBoolean()) zOffset = -zOffset;

        int targetX = config.getCenterX() + xOffset;
        int targetZ = config.getCenterZ() + zOffset;

        world.getChunkAtAsync(targetX >> 4, targetZ >> 4).thenAccept(chunk -> {
            Location loc = findSafeLocationInChunk(world, chunk, targetX, targetZ);
            if (loc != null) {
                performTeleport(player, loc);
                fillCache();
            } else {
                generateAndTeleportDirectly(player, world);
            }
        });
    }

    private void performTeleport(Player player, Location location) {
        player.teleportAsync(location).thenAccept(success -> {
            if (success) {
                cooldownManager.setCooldown(player);
                String msg = config.getMsgTeleported()
                        .replace("%x%", String.valueOf(location.getBlockX()))
                        .replace("%y%", String.valueOf(location.getBlockY()))
                        .replace("%z%", String.valueOf(location.getBlockZ()));
                player.sendMessage(msg);

                if (config.isSoundEnabled() && config.getSound() != null) {
                    player.playSound(location, config.getSound(), config.getSoundVolume(), config.getSoundPitch());
                }
            }
        });
    }
}