package tr.erdemdev.worldInAJar;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.BlockFace;

import java.util.UUID;

public record JarRecord(UUID id, UUID owner, String world, int x, int y, int z,
                        BlockFace door, int cell, int scale, boolean placed) {
    public Location outsideLocation() {
        var loadedWorld = Bukkit.getWorld(world);
        return loadedWorld == null ? null : new Location(loadedWorld, x, y, z);
    }

    public boolean isAt(Location location) {
        return placed && location.getWorld().getName().equals(world)
                && location.getBlockX() == x && location.getBlockY() == y && location.getBlockZ() == z;
    }

    public JarRecord movedTo(Location location, BlockFace newDoor) {
        return new JarRecord(id, owner, location.getWorld().getName(), location.getBlockX(),
                location.getBlockY(), location.getBlockZ(), newDoor, cell, scale, true);
    }

    public JarRecord pickedUp() {
        return new JarRecord(id, owner, world, x, y, z, door, cell, scale, false);
    }
}
