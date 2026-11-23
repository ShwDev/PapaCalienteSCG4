package com.shaw.papacaliente;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Configuración persistente de la arena y el botón: config/papa_caliente.json
 */
public final class ArenaConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("papa_caliente.json");

    private static ArenaConfig instance = new ArenaConfig();

    // --- Arena ---
    public boolean arenaSet = false;
    public String arenaDimension = "minecraft:overworld";
    public int arenaX, arenaY, arenaZ;
    /** Dirección hacia la que miraba el jugador al fijar la arena ("north", "south", "east", "west"). */
    public String arenaFacing = "south";

    // --- Botón ---
    public boolean buttonSet = false;
    public String buttonDimension = "minecraft:overworld";
    public int buttonX, buttonY, buttonZ;
    /** true si el botón lo colocó el mod automáticamente al fijar la arena. */
    public boolean buttonAuto = false;

    public static ArenaConfig get() {
        return instance;
    }

    public BlockPos arenaOrigin() {
        return new BlockPos(arenaX, arenaY, arenaZ);
    }

    public BlockPos buttonPos() {
        return new BlockPos(buttonX, buttonY, buttonZ);
    }

    public Direction facing() {
        Direction d = Direction.byName(arenaFacing);
        return (d == null || d.getAxis().isVertical()) ? Direction.SOUTH : d;
    }

    public void setArena(String dimension, BlockPos pos, Direction facing) {
        arenaSet = true;
        arenaDimension = dimension;
        arenaX = pos.getX();
        arenaY = pos.getY();
        arenaZ = pos.getZ();
        arenaFacing = facing.getName();
        save();
    }

    public void setButton(String dimension, BlockPos pos, boolean auto) {
        buttonSet = true;
        buttonAuto = auto;
        buttonDimension = dimension;
        buttonX = pos.getX();
        buttonY = pos.getY();
        buttonZ = pos.getZ();
        save();
    }

    public static void load() {
        if (!Files.exists(FILE)) {
            instance = new ArenaConfig();
            save();
            return;
        }
        try (Reader r = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
            ArenaConfig loaded = GSON.fromJson(r, ArenaConfig.class);
            instance = loaded != null ? loaded : new ArenaConfig();
        } catch (Exception e) {
            PapaCaliente.LOGGER.error("No se pudo leer {}, se usa configuración vacía", FILE, e);
            instance = new ArenaConfig();
        }
    }

    public static void save() {
        try {
            Files.createDirectories(FILE.getParent());
            try (Writer w = Files.newBufferedWriter(FILE, StandardCharsets.UTF_8)) {
                GSON.toJson(instance, w);
            }
        } catch (IOException e) {
            PapaCaliente.LOGGER.error("No se pudo guardar {}", FILE, e);
        }
    }
}
