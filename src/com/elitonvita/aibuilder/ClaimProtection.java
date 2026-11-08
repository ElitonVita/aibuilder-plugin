package com.elitonvita.aibuilder;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;

/** Optional GriefPrevention bridge. Absence permits standalone use; broken integrations fail closed. */
final class ClaimProtection {
    private ClaimProtection() {}

    private static Object claim(Plugin plugin, Location location) throws ReflectiveOperationException {
        if (!plugin.isEnabled()) throw new IllegalStateException("GriefPrevention is disabled");
        Object store = plugin.getClass().getField("dataStore").get(plugin);
        for (var method : store.getClass().getMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (method.getName().equals("getClaimAt") && parameters.length == 3
                    && parameters[0] == Location.class && parameters[1] == boolean.class)
                return method.invoke(store, location, false, null);
        }
        throw new NoSuchMethodException("GriefPrevention.getClaimAt");
    }

    static boolean claimed(PluginManager manager, Location location) {
        try {
            Plugin gp = manager.getPlugin("GriefPrevention");
            return gp != null && claim(gp, location) != null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) { return true; }
    }

    static boolean canAccess(PluginManager manager, Location location, Player player) {
        try {
            Plugin gp = manager.getPlugin("GriefPrevention");
            Object claim = gp == null ? null : claim(gp, location);
            return claim == null || claim.getClass().getMethod("allowAccess", Player.class).invoke(claim, player) == null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) { return false; }
    }
}
