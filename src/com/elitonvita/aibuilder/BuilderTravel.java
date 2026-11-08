package com.elitonvita.aibuilder;

import org.bukkit.Location;
import org.bukkit.util.BoundingBox;
import java.util.function.Predicate;

/** Bounded, loaded-chunk-only checks shared by recall and the owner's travel limit. */
public final class BuilderTravel {
    private BuilderTravel() {}

    public static boolean within(Location candidate, Location owner, int radius) {
        return candidate != null && owner != null && candidate.getWorld().equals(owner.getWorld())
                && candidate.distanceSquared(owner) <= (double) radius * radius;
    }

    public static Location recallSpot(Location owner, Predicate<Location> permitted, Predicate<BoundingBox> occupied) {
        for (int r = 1; r <= 3; r++) for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
            if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
            for (int dy : new int[]{0, 1, -1, 2, -2}) {
                Location spot = new Location(owner.getWorld(), owner.getBlockX() + dx + .5,
                        owner.getBlockY() + dy, owner.getBlockZ() + dz + .5, owner.getYaw(), 0);
                if (spot.distanceSquared(owner) > 16) continue;
                var cell = new WalkPathfinder.Cell(spot.getBlockX(), spot.getBlockY(), spot.getBlockZ());
                if (!NpcWalker.canStand(spot.getWorld(), cell, permitted)) continue;
                BoundingBox body = new BoundingBox(spot.getX() - .3, spot.getY(), spot.getZ() - .3,
                        spot.getX() + .3, spot.getY() + 2, spot.getZ() + .3);
                if (!occupied.test(body)) return spot;
            }
        }
        return null;
    }
}
