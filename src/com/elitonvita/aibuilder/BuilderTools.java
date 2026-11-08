package com.elitonvita.aibuilder;

import java.util.function.Predicate;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/** Tool cleanup includes the cursor and storage beyond the hotbar. Server thread only. */
public final class BuilderTools {
    private BuilderTools() {}

    public static int remove(Inventory inventory, Predicate<ItemStack> matches) {
        int removed = 0;
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            if (matches.test(inventory.getItem(slot))) {
                inventory.setItem(slot, null);
                removed++;
            }
        }
        return removed;
    }

    public static int remove(Player player, Predicate<ItemStack> matches) {
        int removed = remove(player.getInventory(), matches) + remove(player.getEnderChest(), matches);
        if (matches.test(player.getItemOnCursor())) {
            player.setItemOnCursor(null);
            removed++;
        }
        // This also reaches an open chest, crafting grid or another container.
        return removed + remove(player.getOpenInventory().getTopInventory(), matches);
    }
}
