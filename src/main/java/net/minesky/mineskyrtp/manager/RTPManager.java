package net.minesky.mineskyrtp.manager;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.minesky.mineskyrtp.MineSkyRTP;
import net.minesky.mineskyrtp.config.ConfigManager;
import net.minesky.mineskyrtp.cooldown.CooldownManager;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.Waterlogged;
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
        for (Location loc : cache) {
            releaseChunkLoaded(loc);
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
                    keepChunkLoaded(loc);
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

        String biomeName = chunk.getBlock(blockX, 64, blockZ).getBiome().name();
        if (biomeName.contains("OCEAN") || biomeName.contains("RIVER") || biomeName.contains("SEA")) {
            return null;
        }

        if (world.getEnvironment() == World.Environment.NETHER) {
            for (int y = 120; y >= 32; y--) {
                Block ground = chunk.getBlock(blockX, y, blockZ);
                if (ground.getType().isSolid()) {
                    Block feet = chunk.getBlock(blockX, y + 1, blockZ);
                    Block head = chunk.getBlock(blockX, y + 2, blockZ);

                    if (isSafe(ground, feet, head)) {
                        return new Location(world, x + 0.5, y + 1.0, z + 0.5);
                    }
                }
            }
        } else {
            int highestY = world.getHighestBlockYAt(x, z, HeightMap.WORLD_SURFACE);
            if (highestY <= world.getMinHeight() || highestY >= world.getMaxHeight()) {
                return null;
            }

            Block ground = chunk.getBlock(blockX, highestY - 1, blockZ);
            Block feet = chunk.getBlock(blockX, highestY, blockZ);
            Block head = chunk.getBlock(blockX, highestY + 1, blockZ);

            if (isSafe(ground, feet, head)) {
                return new Location(world, x + 0.5, highestY, z + 0.5);
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

        if (gMat.isAir() || !gMat.isSolid() || ground.isLiquid()) {
            return false;
        }

        if (ground.getBlockData() instanceof Waterlogged && ((Waterlogged) ground.getBlockData()).isWaterlogged()) {
            return false;
        }

        if (feet.isLiquid() || head.isLiquid()) {
            return false;
        }

        if (feet.getBlockData() instanceof Waterlogged && ((Waterlogged) feet.getBlockData()).isWaterlogged()) {
            return false;
        }

        boolean feetPassable = fMat.isAir() || feet.isPassable();
        boolean headPassable = hMat.isAir() || head.isPassable();

        return feetPassable && headPassable;
    }

    private void keepChunkLoaded(Location loc) {
        World world = loc.getWorld();
        if (world == null) return;
        int chunkX = loc.getBlockX() >> 4;
        int chunkZ = loc.getBlockZ() >> 4;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                world.addPluginChunkTicket(chunkX + dx, chunkZ + dz, plugin);
            }
        }
    }

    private void releaseChunkLoaded(Location loc) {
        World world = loc.getWorld();
        if (world == null) return;
        int chunkX = loc.getBlockX() >> 4;
        int chunkZ = loc.getBlockZ() >> 4;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                world.removePluginChunkTicket(chunkX + dx, chunkZ + dz, plugin);
            }
        }
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
                keepChunkLoaded(loc);
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
            plugin.getServer().getAsyncScheduler().runDelayed(plugin, task -> {
                releaseChunkLoaded(location);
            }, 5, TimeUnit.SECONDS);
        });
    }
}