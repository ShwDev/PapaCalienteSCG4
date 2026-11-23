package com.shaw.papacaliente;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * "Modo Selección de Arena": el cliente dibuja la vista previa (ver client/SelectionRenderer) y el
 * servidor espera el clic para fijar la esquina, marcar el terreno y colocar el botón.
 */
public final class SelectionManager {
    private static final int TIMEOUT_TICKS = 20 * 90; // 90 s
    private static final double REACH = 64.0;

    private static final Map<UUID, Integer> SESSIONS = new HashMap<>(); // jugador → tick de inicio
    private static int tick = 0;

    private SelectionManager() {
    }

    public static void start(ServerPlayer player) {
        SESSIONS.put(player.getUUID(), tick);
        sendPreview(player, true);
        player.sendSystemMessage(GameManager.msg("Modo Selección de Arena: apunta a un bloque. Verás el área de "
                + Board.MAX_SIZE + "x" + Board.MAX_SIZE + ", el 3x3 inicial en verde y el botón en celeste. El área va hacia "
                + "delante y a tu derecha según hacia dónde mires. Haz clic para fijarla. /papacaliente cancelar para salir.",
                ChatFormatting.YELLOW));
        Fx.soundTo(player, SoundEvents.EXPERIENCE_ORB_PICKUP, 0.6f, 1.4f);
    }

    public static boolean cancel(ServerPlayer player) {
        boolean had = SESSIONS.remove(player.getUUID()) != null;
        if (had) sendPreview(player, false);
        return had;
    }

    public static boolean isSelecting(ServerPlayer player) {
        return SESSIONS.containsKey(player.getUUID());
    }

    /** Clic sobre un bloque estando en modo selección: fija la arena. */
    public static void handleClick(ServerPlayer player, BlockPos pos) {
        if (SESSIONS.remove(player.getUUID()) == null) return;
        sendPreview(player, false);

        ServerLevel level = player.serverLevel();
        String dim = level.dimension().location().toString();
        Direction facing = player.getDirection();
        BlockPos button = setupArena(level, pos, facing);
        ArenaConfig cfg = ArenaConfig.get();
        cfg.setArena(dim, pos, facing);
        cfg.setButton(dim, button, true);

        player.sendSystemMessage(GameManager.msg("Arena de " + Board.MAX_SIZE + "x" + Board.MAX_SIZE + " fijada en "
                + pos.toShortString() + " (hacia " + dirName(facing) + "). Botón colocado a la derecha en "
                + button.toShortString() + ".", ChatFormatting.GREEN));
        Fx.soundTo(player, SoundEvents.PLAYER_LEVELUP, 0.8f, 1.6f);
        Fx.title(player, Component.literal("✔").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD),
                Component.literal("Arena lista").withStyle(ChatFormatting.GREEN), 3, 25, 8);
    }

    public static void tick(MinecraftServer server) {
        tick++;
        if (SESSIONS.isEmpty() || tick % 5 != 0) return;
        Iterator<Map.Entry<UUID, Integer>> it = SESSIONS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Integer> e = it.next();
            ServerPlayer player = server.getPlayerList().getPlayer(e.getKey());
            if (player == null) {
                it.remove();
                continue;
            }
            if (tick - e.getValue() > TIMEOUT_TICKS) {
                it.remove();
                sendPreview(player, false);
                player.sendSystemMessage(GameManager.msg("Modo selección cancelado por inactividad.", ChatFormatting.GRAY));
                continue;
            }
            HitResult hit = player.pick(REACH, 1.0f, false);
            if (hit.getType() == HitResult.Type.BLOCK) {
                BlockPos pos = ((BlockHitResult) hit).getBlockPos();
                Fx.actionBar(player, Component.literal("Esquina " + pos.toShortString() + "  ·  hacia "
                        + dirName(player.getDirection()) + "  ·  clic para fijar").withStyle(ChatFormatting.GOLD));
            } else {
                Fx.actionBar(player, Component.literal("Apunta a un bloque…").withStyle(ChatFormatting.GRAY));
            }
        }
    }

    private static void sendPreview(ServerPlayer player, boolean active) {
        if (ServerPlayNetworking.canSend(player, SelectionPayload.TYPE)) {
            ServerPlayNetworking.send(player, new SelectionPayload(active));
        }
    }

    /**
     * Marca el área máxima con tierra gruesa y coloca el botón automáticamente a la derecha.
     * @return posición del botón
     */
    private static BlockPos setupArena(ServerLevel level, BlockPos origin, Direction forward) {
        ArenaConfig cfg = ArenaConfig.get();
        // Quitar el botón automático de una arena anterior
        if (cfg.buttonSet && cfg.buttonAuto && cfg.buttonDimension.equals(level.dimension().location().toString())) {
            BlockPos old = cfg.buttonPos();
            if (level.getBlockState(old).getBlock() instanceof ButtonBlock) {
                level.setBlockAndUpdate(old, Blocks.AIR.defaultBlockState());
            }
        }
        BlockState marker = Blocks.COARSE_DIRT.defaultBlockState();
        for (int i = 0; i < Board.MAX_SIZE; i++) {
            for (int j = 0; j < Board.MAX_SIZE; j++) {
                BlockPos p = Board.cell(origin, forward, i, j);
                level.setBlockAndUpdate(p, marker);
                Fx.particles(level, new BlockParticleOption(ParticleTypes.BLOCK, marker),
                        p.getX() + 0.5, p.getY() + 1.0, p.getZ() + 0.5, 4, 0.3, 0.05);
            }
        }
        BlockPos support = Board.buttonSupport(origin, forward);
        BlockPos button = Board.buttonPos(origin, forward);
        level.setBlockAndUpdate(support, Blocks.POLISHED_BLACKSTONE.defaultBlockState());
        level.setBlockAndUpdate(button, Blocks.POLISHED_BLACKSTONE_BUTTON.defaultBlockState()
                .setValue(BlockStateProperties.ATTACH_FACE, AttachFace.FLOOR)
                .setValue(BlockStateProperties.HORIZONTAL_FACING, forward));
        Fx.sound(level, button, SoundEvents.STONE_PLACE, 1.0f, 1.0f);
        return button;
    }

    public static String dirName(Direction d) {
        return switch (d) {
            case NORTH -> "el norte (-Z)";
            case SOUTH -> "el sur (+Z)";
            case EAST -> "el este (+X)";
            case WEST -> "el oeste (-X)";
            default -> d.getName();
        };
    }
}
