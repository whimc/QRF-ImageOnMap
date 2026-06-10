/*
 * Copyright or © or Copr. QuartzLib contributors (2015 - 2020)
 * Patched for Minecraft 1.21+ by QRF-ImageOnMap contributors.
 *
 * This software is governed by the CeCILL-B license under French law and
 * abiding by the rules of distribution of free software.  You can use,
 * modify and/ or redistribute the software under the terms of the CeCILL-B
 * license as circulated by CEA, CNRS and INRIA at the following URL
 * "http://www.cecill.info".
 */

package fr.zcraft.quartzlib.tools.items;

import fr.zcraft.quartzlib.core.QuartzComponent;
import fr.zcraft.quartzlib.core.QuartzLib;
import java.util.Map;
import org.bukkit.NamespacedKey;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/**
 * Utilities for adding a glowing effect on any item.
 *
 * <p>On Minecraft 1.20.5+, this uses {@link ItemMeta#setEnchantmentGlintOverride(Boolean)}
 * instead of registering a fake enchantment (which no longer works on 1.21+).</p>
 */
public class GlowEffect extends QuartzComponent {
    private static final String GLOW_MARKER_KEY = "glow_effect";

    @Override
    protected void onEnable() {
        QuartzLib.registerEvents(new GlowEnchantEventListener());
    }

    private static NamespacedKey getGlowMarkerKey() {
        return new NamespacedKey(QuartzLib.getPlugin(), GLOW_MARKER_KEY);
    }

    /**
     * Adds a glowing effect to the given item stack.
     *
     * @param item The item.
     */
    public static void addGlow(ItemStack item) {
        if (item == null) {
            return;
        }

        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return;
        }

        meta.setEnchantmentGlintOverride(true);
        meta.getPersistentDataContainer().set(getGlowMarkerKey(), PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
    }

    /**
     * Removes a previously-added glowing effect from the given item.
     *
     * @param item The item.
     */
    public static void removeGlow(ItemStack item) {
        if (item == null) {
            return;
        }

        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return;
        }

        meta.getPersistentDataContainer().remove(getGlowMarkerKey());
        if (meta.hasEnchantmentGlintOverride()) {
            meta.setEnchantmentGlintOverride(null);
        }
        item.setItemMeta(meta);
    }

    /**
     * Returns if the given item has the glowing effect applied to it.
     *
     * @param item The item.
     * @return if the given item has the glowing effect applied to it.
     */
    public static boolean hasGlow(ItemStack item) {
        if (item == null) {
            return false;
        }

        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return false;
        }

        return meta.getPersistentDataContainer().has(getGlowMarkerKey(), PersistentDataType.BYTE);
    }

    private static class GlowEnchantEventListener implements Listener {
        @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
        public void onInventoryClick(InventoryClickEvent event) {
            if (event.getInventory().getType() != InventoryType.GRINDSTONE) {
                return;
            }

            if (event.getClick().isShiftClick() && hasGlow(event.getCurrentItem())) {
                event.setResult(Event.Result.DENY);
                return;
            }

            Inventory clickedInventory = event.getClickedInventory();
            if (clickedInventory == null || clickedInventory.getType() != InventoryType.GRINDSTONE) {
                return;
            }

            if (hasGlow(event.getCursor())) {
                event.setResult(Event.Result.DENY);
                return;
            }

            for (ItemStack item : clickedInventory) {
                if (hasGlow(item)) {
                    event.setResult(Event.Result.DENY);
                    return;
                }
            }
        }

        @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
        public void onInventoryDrag(InventoryDragEvent event) {
            if (event.getInventory().getType() != InventoryType.GRINDSTONE) {
                return;
            }

            for (Map.Entry<Integer, ItemStack> entry : event.getNewItems().entrySet()) {
                Inventory dragResult = event.getView().getInventory(entry.getKey());
                if (dragResult != null && dragResult.getType() == InventoryType.GRINDSTONE) {
                    if (hasGlow(entry.getValue())) {
                        event.setResult(Event.Result.DENY);
                        return;
                    }
                }
            }
        }
    }
}
