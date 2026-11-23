package com.shaw.papacaliente;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.Unbreakable;

import java.util.List;

/**
 * Ítems del minijuego. Se usan ítems vanilla con Data Components (1.21.1), así el mod
 * no registra ítems nuevos y los clientes no necesitan nada especial para verlos.
 */
public final class GameItems {
    private static final String TAG = "papa_caliente";
    public static final String BOMB = "bomba";
    public static final String SHOVEL = "pala";

    private GameItems() {
    }

    public static ItemStack bomb(int count) {
        ItemStack stack = new ItemStack(Items.POTATO, count);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("Papa Bomba")
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD).withStyle(s -> s.withItalic(false)));
        stack.set(DataComponents.LORE, new ItemLore(List.of(
                Component.literal("Clic derecho sobre tierra de la arena").withStyle(ChatFormatting.GRAY),
                Component.literal("para enterrarla. ¡Recuerda dónde!").withStyle(ChatFormatting.GRAY))));
        stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        stack.set(DataComponents.CUSTOM_DATA, tag(BOMB));
        return stack;
    }

    public static ItemStack shovel() {
        ItemStack stack = new ItemStack(Items.IRON_SHOVEL);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("Pala de Papa Caliente")
                .withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD).withStyle(s -> s.withItalic(false)));
        stack.set(DataComponents.LORE, new ItemLore(List.of(
                Component.literal("Clic izquierdo sobre la tierra de la arena").withStyle(ChatFormatting.GRAY),
                Component.literal("en tu turno para excavar.").withStyle(ChatFormatting.GRAY))));
        stack.set(DataComponents.UNBREAKABLE, new Unbreakable(false));
        stack.set(DataComponents.CUSTOM_DATA, tag(SHOVEL));
        return stack;
    }

    private static CustomData tag(String kind) {
        CompoundTag nbt = new CompoundTag();
        nbt.putString(TAG, kind);
        return CustomData.of(nbt);
    }

    public static boolean is(ItemStack stack, String kind) {
        if (stack == null || stack.isEmpty()) return false;
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return false;
        return kind.equals(data.copyTag().getString(TAG));
    }

    public static boolean isGameItem(ItemStack stack) {
        return is(stack, BOMB) || is(stack, SHOVEL);
    }

    public static int count(ServerPlayer player, String kind) {
        Inventory inv = player.getInventory();
        int total = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (is(s, kind)) total += s.getCount();
        }
        return total;
    }

    public static void removeAll(ServerPlayer player) {
        Inventory inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (isGameItem(inv.getItem(i))) inv.setItem(i, ItemStack.EMPTY);
        }
        if (isGameItem(player.containerMenu.getCarried())) player.containerMenu.setCarried(ItemStack.EMPTY);
        player.containerMenu.broadcastChanges();
    }

    public static void give(ServerPlayer player, ItemStack stack) {
        if (!player.getInventory().add(stack) && !stack.isEmpty()) {
            player.drop(stack, false);
        }
        player.containerMenu.broadcastChanges();
    }
}
