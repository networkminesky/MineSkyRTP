package net.minesky.mineskyrtp.manager;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.minesky.mineskyrtp.MineSkyRTP;
import net.minesky.mineskyrtp.config.ConfigManager;
import net.minesky.mineskyrtp.cooldown.CooldownManager;
import net.minesky.mineskyrtp.storage.LocationStorage;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class RTPManager {

    private final MineSkyRTP plugin;
    private final ConfigManager config;
    private final CooldownManager cooldownManager;
    private final LocationStorage locationStorage;
    private final Queue<Location> cache = new ConcurrentLinkedQueue<>();
    private final Random random = new Random();
    private final AtomicBoolean isGenerating = new AtomicBoolean(false);
    private ScheduledTask asyncTask;

    public RTPManager(MineSkyRTP plugin, ConfigManager config, CooldownManager cooldownManager) {
        this.plugin = plugin;
        this.config = config;
        this.cooldownManager = cooldownManager;
        this.locationStorage = new LocationStorage(plugin);
        loadSavedCache();
    }

    private void loadSavedCache() {
        List<Location> saved = locationStorage.loadSavedLocations();
        for (Location loc : saved) {
            keepChunkLoaded(loc);
            cache.add(loc);
        }
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
        locationStorage.saveLocations(cache);
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

        findLocationAsync(world, 0, 0.0, 0.0);
    }

    private void findLocationAsync(World world, int localStep, double baseAngle, double baseDist) {
        WorldBorder border = world.getWorldBorder();
        double borderSize = border.getSize() / 2.0;
        double borderCenterX = border.getCenter().getX();
        double borderCenterZ = border.getCenter().getZ();

        int minR = config.getMinRadius();
        int maxR = (int) Math.min(config.getMaxRadius(), borderSize - 32);
        if (maxR <= minR) {
            maxR = minR + 100;
        }

        double angle = baseAngle;
        double distance = baseDist;

        if (localStep == 0) {
            angle = random.nextDouble() * 2 * Math.PI;
            distance = minR + (random.nextDouble() * (maxR - minR));
        } else {
            angle = baseAngle + (localStep * 0.2);
            distance = baseDist + (localStep * 64);
            if (distance > maxR) {
                distance = minR + (distance % (maxR - minR));
            }
        }

        int targetX = (int) (config.getCenterX() + distance * Math.cos(angle));
        int targetZ = (int) (config.getCenterZ() + distance * Math.sin(angle));

        if (Math.abs(targetX - borderCenterX) >= borderSize - 16 || Math.abs(targetZ - borderCenterZ) >= borderSize - 16) {
            targetX = (int) borderCenterX;
            targetZ = (int) borderCenterZ;
        }

        int chunkX = targetX >> 4;
        int chunkZ = targetZ >> 4;

        if (localStep < 3 && !world.isChunkGenerated(chunkX, chunkZ)) {
            findLocationAsync(world, localStep + 1, angle, distance);
            return;
        }

        final double currentAngle = angle;
        final double currentDist = distance;

        final int finalTargetX = targetX;
        final int finalTargetZ = targetZ;

        world.getChunkAtAsync(chunkX, chunkZ).thenAccept(chunk -> {
            try {
                Location loc = findSafeLocationInChunk(world, chunk, finalTargetX, finalTargetZ);
                if (loc != null) {
                    keepChunkLoaded(loc);
                    cache.add(loc);
                    locationStorage.saveLocations(cache);
                } else if (localStep < 4) {
                    findLocationAsync(world, localStep + 1, currentAngle, currentDist);
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
        if (biomeName.contains("OCEAN") || biomeName.contains("RIVER") || biomeName.contains("SEA") || biomeName.contains("SWAMP")) {
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
            locationStorage.saveLocations(cache);
            performTeleport(player, cachedLocation);
            fillCache();
        } else {
            player.sendMessage(config.getMsgNoLocation());
            World world = Bukkit.getWorld(config.getWorldName());
            if (world == null) {
                player.sendMessage(config.getMsgWorldNotFound());
                return;
            }
            generateAndTeleportDirectly(player, world, 0, 0.0, 0.0);
        }
    }

    private void generateAndTeleportDirectly(Player player, World world, int localStep, double baseAngle, double baseDist) {
        WorldBorder border = world.getWorldBorder();
        double borderSize = border.getSize() / 2.0;
        double borderCenterX = border.getCenter().getX();
        double borderCenterZ = border.getCenter().getZ();

        int minR = config.getMinRadius();
        int maxR = (int) Math.min(config.getMaxRadius(), borderSize - 32);
        if (maxR <= minR) maxR = minR + 100;

        double angle = baseAngle;
        double distance = baseDist;

        if (localStep == 0) {
            angle = random.nextDouble() * 2 * Math.PI;
            distance = minR + (random.nextDouble() * (maxR - minR));
        } else {
            angle = baseAngle + (localStep * 0.2);
            distance = baseDist + (localStep * 64);
            if (distance > maxR) distance = minR + (distance % (maxR - minR));
        }

        int targetX = (int) (config.getCenterX() + distance * Math.cos(angle));
        int targetZ = (int) (config.getCenterZ() + distance * Math.sin(angle));

        if (Math.abs(targetX - borderCenterX) >= borderSize - 16 || Math.abs(targetZ - borderCenterZ) >= borderSize - 16) {
            targetX = (int) borderCenterX;
            targetZ = (int) borderCenterZ;
        }

        int chunkX = targetX >> 4;
        int chunkZ = targetZ >> 4;

        if (localStep < 3 && !world.isChunkGenerated(chunkX, chunkZ)) {
            generateAndTeleportDirectly(player, world, localStep + 1, angle, distance);
            return;
        }

        final double currentAngle = angle;
        final double currentDist = distance;

        final int finalTargetX = targetX;
        final int finalTargetZ = targetZ;

        world.getChunkAtAsync(chunkX, chunkZ).thenAccept(chunk -> {
            Location loc = findSafeLocationInChunk(world, chunk, finalTargetX, finalTargetZ);
            if (loc != null) {
                keepChunkLoaded(loc);
                performTeleport(player, loc);
                fillCache();
            } else {
                int nextStep = (localStep >= 8) ? 0 : localStep + 1;
                generateAndTeleportDirectly(player, world, nextStep, currentAngle, currentDist);
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