package com.shaw.papacaliente;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;

/** Bloques propios del tablero (solo se colocan por el juego, no tienen ítem). */
public final class ModBlocks {
    /** Tierra del tablero con cuadrícula clara en la cara superior. */
    public static final Block ARENA_DIRT = register("arena_dirt",
            new Block(BlockBehaviour.Properties.ofFullCopy(Blocks.DIRT)));

    /** Casilla con un montoncito de piedras encima (obstáculo). */
    public static final Block ARENA_ROCK = register("arena_rock",
            new Block(BlockBehaviour.Properties.ofFullCopy(Blocks.DIRT).sound(SoundType.STONE).noOcclusion()));

    private ModBlocks() {
    }

    private static Block register(String name, Block block) {
        return Registry.register(BuiltInRegistries.BLOCK, ResourceLocation.fromNamespaceAndPath(PapaCaliente.MOD_ID, name), block);
    }

    /** Fuerza la carga de la clase (y por tanto el registro) durante la inicialización del mod. */
    public static void init() {
    }
}
