package com.shaw.papacaliente;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Máquina de estados del minijuego (100 % del lado del servidor).
 * LOBBY → PLACING_BOMBS → DIGGING → ROUND_TRANSITION → (siguiente ronda | GAME_OVER) → LOBBY
 */
public final class GameManager {
    public enum Phase { LOBBY, PLACING_BOMBS, DIGGING, ROUND_TRANSITION, GAME_OVER }

    /** Bloques de tierra seguros mínimos garantizados además de todas las papas. */
    private static final int SAFE_MIN = 3;
    private static final int GAME_OVER_TICKS = 20 * 6;
    private static final int NEXT_ROUND_TICKS = 20 * 4;

    private static final Random RANDOM = new Random();
    private static final BlockState DIRT = ModBlocks.ARENA_DIRT.defaultBlockState();
    private static final BlockState STONE = ModBlocks.ARENA_ROCK.defaultBlockState();
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    private static MinecraftServer server;
    private static Phase phase = Phase.LOBBY;

    private static final List<UUID> players = new ArrayList<>(2);
    private static final Map<UUID, String> names = new HashMap<>();

    // Arena de la partida en curso
    private static ServerLevel level;
    private static BlockPos origin;
    private static Direction forward;
    private static ServerLevel buttonLevel;
    private static BlockPos buttonPos;

    private static final Set<Long> allCells = new HashSet<>();   // área máxima completa (capa del tablero)
    private static final Set<Long> board = new HashSet<>();      // casillas de la ronda actual
    private static final Set<Long> stones = new HashSet<>();
    private static final Set<Long> dug = new HashSet<>();
    private static final Map<Long, UUID> bombs = new HashMap<>(); // posición → dueño
    private static final Map<Long, BlockState> snapshot = new HashMap<>();

    private static int round;
    private static int size;
    private static int turn;        // índice 0/1 del jugador con el turno
    private static int digStarter;
    private static final int[] remaining = new int[2];

    // Entidades temporales (papas flotantes) y tareas programadas
    private static final List<Entity> tempEntities = new ArrayList<>();
    private record Task(int due, int generation, Runnable action) {
    }
    private static final List<Task> tasks = new ArrayList<>();
    private static int tick = 0;
    private static int generation = 0;

    private GameManager() {
    }

    // ------------------------------------------------------------------ ciclo de vida

    public static void init(MinecraftServer s) {
        server = s;
    }

    public static void shutdown() {
        if (phase != Phase.LOBBY) stop("El servidor se está cerrando.");
        server = null;
    }

    public static Phase phase() {
        return phase;
    }

    public static boolean isActive() {
        return phase != Phase.LOBBY;
    }

    public static boolean isParticipant(ServerPlayer p) {
        return players.contains(p.getUUID());
    }

    public static void tick() {
        tick++;
        if (!tasks.isEmpty()) {
            List<Task> due = new ArrayList<>();
            tasks.removeIf(t -> {
                if (t.generation != generation) return true;
                if (t.due <= tick) {
                    due.add(t);
                    return true;
                }
                return false;
            });
            for (Task t : due) {
                if (t.generation == generation) t.action.run();
            }
        }
        if (phase == Phase.LOBBY) return;
        if (tick % 10 == 0) hud();
        if (tick % 20 == 0) ensureItems();
    }

    private static void schedule(int delayTicks, Runnable r) {
        tasks.add(new Task(tick + delayTicks, generation, r));
    }

    // ------------------------------------------------------------------ comandos

    public static void join(ServerPlayer p) {
        if (players.contains(p.getUUID())) {
            p.sendSystemMessage(msg("Ya estás en la partida.", ChatFormatting.YELLOW));
            return;
        }
        if (phase != Phase.LOBBY) {
            p.sendSystemMessage(msg("Hay una partida en curso, espera a que termine.", ChatFormatting.RED));
            return;
        }
        if (players.size() >= 2) {
            p.sendSystemMessage(msg("La partida ya tiene 2 jugadores.", ChatFormatting.RED));
            return;
        }
        players.add(p.getUUID());
        names.put(p.getUUID(), p.getName().getString());
        broadcast(msg(p.getName().getString() + " se unió a Papa Caliente (" + players.size() + "/2).", ChatFormatting.GREEN));
        Fx.soundTo(p, SoundEvents.EXPERIENCE_ORB_PICKUP, 0.7f, 1.2f);
        if (players.size() == 2) {
            broadcast(msg("¡Listos! Usa /papacaliente start para comenzar.", ChatFormatting.GOLD));
        }
    }

    public static void leave(ServerPlayer p) {
        if (!players.contains(p.getUUID())) {
            p.sendSystemMessage(msg("No estás en la partida.", ChatFormatting.YELLOW));
            return;
        }
        if (phase == Phase.LOBBY) {
            players.remove(p.getUUID());
            p.sendSystemMessage(msg("Saliste de la sala.", ChatFormatting.YELLOW));
            broadcast(msg(p.getName().getString() + " salió de la sala.", ChatFormatting.YELLOW));
        } else {
            forfeit(p.getUUID(), p.getName().getString() + " abandonó la partida.");
            players.remove(p.getUUID());
        }
    }

    /** @return mensaje de error o null si arrancó. */
    public static String start() {
        if (phase != Phase.LOBBY) return "Ya hay una partida en curso.";
        ArenaConfig cfg = ArenaConfig.get();
        if (!cfg.arenaSet) return "Primero configura la arena con /papacaliente setarena.";
        if (!cfg.buttonSet) return "Falta el botón: vuelve a fijar la arena con /papacaliente setarena.";
        if (players.size() != 2) return "Se necesitan exactamente 2 jugadores (/papacaliente join). Ahora hay " + players.size() + ".";
        for (UUID id : players) {
            if (player(id) == null) return names.getOrDefault(id, "Un jugador") + " no está conectado.";
        }
        ServerLevel lvl = levelById(cfg.arenaDimension);
        ServerLevel blvl = levelById(cfg.buttonDimension);
        if (lvl == null) return "La dimensión de la arena (" + cfg.arenaDimension + ") no existe.";
        if (blvl == null) return "La dimensión del botón (" + cfg.buttonDimension + ") no existe.";

        level = lvl;
        origin = cfg.arenaOrigin();
        forward = cfg.facing();
        buttonLevel = blvl;
        buttonPos = cfg.buttonPos();

        allCells.clear();
        for (int i = 0; i < Board.MAX_SIZE; i++) {
            for (int j = 0; j < Board.MAX_SIZE; j++) {
                allCells.add(Board.cell(origin, forward, i, j).asLong());
            }
        }
        if (buttonLevel == level && inArenaVolume(buttonPos)) {
            allCells.clear();
            return "El botón está dentro del área " + Board.MAX_SIZE + "x" + Board.MAX_SIZE + " de la arena (o justo encima). Colócalo fuera del tablero.";
        }

        // Guardar lo que había para restaurarlo al terminar
        snapshot.clear();
        for (long key : allCells) {
            BlockPos base = BlockPos.of(key);
            for (int dy = 0; dy <= 2; dy++) {
                BlockPos p = base.above(dy);
                snapshot.put(p.asLong(), level.getBlockState(p));
            }
        }

        generation++;
        tasks.clear();
        broadcast(msg("¡Comienza Papa Caliente! " + name(0) + " vs " + name(1) + ". " + Board.MAX_ROUND + " rondas, de 3x3 a " + Board.MAX_SIZE + "x" + Board.MAX_SIZE + ", creciendo desde el centro.", ChatFormatting.GOLD));
        beginRound(1);
        return null;
    }

    public static void stop(String reason) {
        boolean wasActive = phase != Phase.LOBBY;
        generation++;
        tasks.clear();
        if (wasActive) {
            cleanupArena();
        }
        broadcast(msg(reason, ChatFormatting.RED));
        players.clear();
        phase = Phase.LOBBY;
    }

    public static Component status() {
        ArenaConfig cfg = ArenaConfig.get();
        MutableComponent c = Component.literal("—— Papa Caliente ——\n").withStyle(ChatFormatting.GOLD);
        c.append(line("Fase", phaseName()));
        c.append(line("Jugadores", players.isEmpty() ? "ninguno" : String.join(", ", players.stream().map(id -> names.getOrDefault(id, "?")).toList())));
        if (phase != Phase.LOBBY) c.append(line("Ronda", round + "/" + Board.MAX_ROUND + " (" + size + "x" + size + ")"));
        c.append(line("Arena", cfg.arenaSet ? cfg.arenaOrigin().toShortString() + " hacia " + SelectionManager.dirName(cfg.facing()) + " [" + cfg.arenaDimension + "]" : "sin configurar"));
        c.append(line("Botón", cfg.buttonSet ? cfg.buttonPos().toShortString() + " [" + cfg.buttonDimension + "]" : "sin configurar"));
        return c;
    }

    private static Component line(String k, String v) {
        return Component.literal(k + ": ").withStyle(ChatFormatting.YELLOW)
                .append(Component.literal(v + "\n").withStyle(ChatFormatting.WHITE));
    }

    // ------------------------------------------------------------------ rondas

    /** Duración (ticks) de la nube de tierra y momento en que el tablero se rehace bajo ella. */
    private static final int CLOUD_TICKS = 32;
    private static final int REBUILD_AT = 12;
    private static final int PLACING_AT = CLOUD_TICKS + 20;

    private static void beginRound(int r) {
        phase = Phase.ROUND_TRANSITION;
        round = r;
        size = Board.sizeForRound(r);
        Set<Long> previous = new HashSet<>(board);
        bombs.clear();
        stones.clear();
        dug.clear();
        board.clear();
        for (int a = 0; a < size; a++) {
            for (int b = 0; b < size; b++) {
                board.add(Board.boardCell(origin, forward, size, a, b).asLong());
            }
        }

        for (UUID id : players) {
            ServerPlayer p = player(id);
            if (p == null) continue;
            GameItems.removeAll(p);
            Fx.title(p, Component.literal(r == 1 ? "¡Prepárense!" : "¡Ronda completa!").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD),
                    Component.literal("Rellenando la zona para la ronda " + r + " (" + size + "x" + size + ")…")
                            .withStyle(ChatFormatting.YELLOW), 5, 50, 10);
        }

        // Animación: una nube densa de tierra cubre la arena, el tablero se rehace por debajo
        // y la nube se disipa dejando el terreno expandido.
        for (int t = 0; t < CLOUD_TICKS; t++) {
            float intensity = t < 16 ? 1.0f : 1.0f - (t - 16) / (float) (CLOUD_TICKS - 16) * 0.8f;
            int tt = t;
            schedule(t, () -> cloudPuff(intensity, tt));
        }
        schedule(REBUILD_AT, () -> rebuildBoard(previous));
        schedule(PLACING_AT, () -> startPlacing(r));
    }

    /** Rehace el área: tablero nuevo de tierra (rellenando hoyos), fuera de él lo original, encima aire. */
    private static void rebuildBoard(Set<Long> previous) {
        clearTempEntities();
        for (long key : allCells) {
            BlockPos p = BlockPos.of(key);
            level.setBlockAndUpdate(p, board.contains(key) ? DIRT : snapshot.getOrDefault(key, AIR));
            level.setBlockAndUpdate(p.above(1), AIR);
            level.setBlockAndUpdate(p.above(2), AIR);
        }
        BlockPos center = arenaCenter();
        Fx.sound(level, center, SoundEvents.ROOTED_DIRT_PLACE, 1.5f, 0.5f);
        Fx.sound(level, center, SoundEvents.GRAVEL_PLACE, 1.5f, 0.6f);
    }

    private static void cloudPuff(float intensity, int t) {
        double[] r = arenaRect();
        double y0 = origin.getY() + 1.0;
        int bursts = Math.max(1, Math.round(6 * intensity));
        for (int k = 0; k < bursts; k++) {
            double x = r[0] + RANDOM.nextDouble() * (r[2] - r[0]);
            double z = r[1] + RANDOM.nextDouble() * (r[3] - r[1]);
            double y = y0 + 0.3 + RANDOM.nextDouble() * 2.2;
            level.sendParticles(ModParticles.DIRT_CLOUD, x, y, z, 6, 1.1, 0.6, 1.1, 0.02);
        }
        // Tierra que cae desde arriba al principio
        if (t < 18) {
            BlockParticleOption falling = new BlockParticleOption(ParticleTypes.FALLING_DUST, Blocks.DIRT.defaultBlockState());
            double cx = (r[0] + r[2]) / 2, cz = (r[1] + r[3]) / 2;
            level.sendParticles(falling, cx, y0 + 5.0, cz, 25, (r[2] - r[0]) / 3, 1.0, (r[3] - r[1]) / 3, 0);
            level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.DIRT.defaultBlockState()),
                    cx, y0 + 0.5, cz, 20, (r[2] - r[0]) / 3, 0.3, (r[3] - r[1]) / 3, 0.15);
        }
        if (t % 3 == 0 && t < 24) {
            BlockPos at = BlockPos.containing(r[0] + RANDOM.nextDouble() * (r[2] - r[0]), y0, r[1] + RANDOM.nextDouble() * (r[3] - r[1]));
            Fx.sound(level, at, t % 2 == 0 ? SoundEvents.GRAVEL_FALL : SoundEvents.ROOTED_DIRT_BREAK,
                    1.2f, 0.6f + RANDOM.nextFloat() * 0.4f);
        }
    }

    /** Rectángulo (x0, z0, x1, z1) del área máxima en coordenadas de mundo. */
    private static double[] arenaRect() {
        BlockPos a = Board.cell(origin, forward, 0, 0);
        BlockPos b = Board.cell(origin, forward, Board.MAX_SIZE - 1, Board.MAX_SIZE - 1);
        return new double[]{Math.min(a.getX(), b.getX()), Math.min(a.getZ(), b.getZ()),
                Math.max(a.getX(), b.getX()) + 1, Math.max(a.getZ(), b.getZ()) + 1};
    }

    private static BlockPos arenaCenter() {
        return Board.cell(origin, forward, Board.MAX_SIZE / 2, Board.MAX_SIZE / 2);
    }

    private static void startPlacing(int r) {
        // Papas por jugador y piedras con la regla de balance:
        // tierra libre >= papas J1 + papas J2 + SAFE_MIN
        int perPlayer = Board.bombsPerPlayer(r);
        remaining[0] = perPlayer;
        remaining[1] = perPlayer;
        int cells = size * size;
        int maxByBalance = Math.max(0, cells - 2 * perPlayer - SAFE_MIN);
        int minStones = (r == 1) ? 0 : size / 2;
        int maxStones = (r == 1) ? 2 : size;
        maxStones = Math.min(maxStones, maxByBalance);
        minStones = Math.min(minStones, maxStones);
        int stoneCount = minStones + RANDOM.nextInt(maxStones - minStones + 1);

        List<Long> boardList = new ArrayList<>(board);
        Collections.shuffle(boardList, RANDOM);
        for (int k = 0; k < stoneCount; k++) {
            long key = boardList.get(k);
            stones.add(key);
            BlockPos p = BlockPos.of(key);
            level.setBlockAndUpdate(p, STONE);
            Fx.particles(level, new BlockParticleOption(ParticleTypes.BLOCK, STONE),
                    p.getX() + 0.5, p.getY() + 1.0, p.getZ() + 0.5, 10, 0.3, 0.05);
        }

        // Turnos: se alterna quién empieza en cada ronda; excava primero el otro
        int starter = (r % 2 == 1) ? 0 : 1;
        turn = starter;
        digStarter = 1 - starter;
        phase = Phase.PLACING_BOMBS;

        for (UUID id : players) {
            ServerPlayer p = player(id);
            if (p == null) continue;
            GameItems.removeAll(p);
            GameItems.give(p, GameItems.bomb(perPlayer));
            Fx.title(p, Component.literal("¡A enterrar!").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD),
                    Component.literal("Tablero " + size + "x" + size + " · " + perPlayer + " papa(s) cada uno · "
                            + stoneCount + " piedra(s)").withStyle(ChatFormatting.YELLOW), 5, 40, 10);
        }
        BlockPos center = Board.cell(origin, forward, Board.MAX_SIZE / 2, Board.MAX_SIZE / 2);
        Fx.sound(level, center, SoundEvents.PLAYER_LEVELUP, 1.0f, 1.0f);
        Fx.sound(level, center, SoundEvents.STONE_PLACE, 1.0f, 0.8f);
        broadcast(msg("Ronda " + r + " (" + size + "x" + size + "): Fase de ENTIERRO. Cada uno entierra " + perPlayer
                + " Papa(s) Bomba con clic derecho sobre la tierra: primero uno entierra todas las suyas y luego el otro. ¡Memoriza dónde quedan!", ChatFormatting.GOLD));
        schedule(30, GameManager::announceTurn);
    }

    private static void startDigging() {
        phase = Phase.DIGGING;
        turn = digStarter;
        for (UUID id : players) {
            ServerPlayer p = player(id);
            if (p == null) continue;
            GameItems.removeAll(p);
            GameItems.give(p, GameItems.shovel());
            Fx.title(p, Component.literal("¡A excavar!").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD),
                    Component.literal("Clic izquierdo con la pala · o pulsa el botón si ya no quedan seguros")
                            .withStyle(ChatFormatting.GRAY), 5, 40, 10);
        }
        broadcast(msg("Fase de EXCAVACIÓN: por turnos, cava un bloque de tierra. Si crees que toda la tierra restante "
                + "tiene bombas, pulsa el botón en tu turno.", ChatFormatting.AQUA));
        schedule(20, GameManager::announceTurn);
    }

    private static void announceTurn() {
        if (phase != Phase.PLACING_BOMBS && phase != Phase.DIGGING) return;
        ServerPlayer cur = player(players.get(turn));
        String what = phase == Phase.PLACING_BOMBS
                ? "Entierra tus " + remaining[turn] + " Papa(s) Bomba"
                : "Cava un bloque o pulsa el botón";
        if (cur != null) {
            Fx.title(cur, Component.literal("¡Tu turno!").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD),
                    Component.literal(what).withStyle(ChatFormatting.YELLOW), 3, 25, 6);
            Fx.soundTo(cur, SoundEvents.EXPERIENCE_ORB_PICKUP, 0.9f, 1.0f);
        }
        ServerPlayer other = player(players.get(1 - turn));
        if (other != null) {
            other.sendSystemMessage(msg("Turno de " + name(turn) + ".", ChatFormatting.GRAY));
        }
    }

    // ------------------------------------------------------------------ fase 1: enterrar

    private static void bury(ServerPlayer p, InteractionHand hand, BlockPos pos) {
        int idx = players.indexOf(p.getUUID());
        if (phase != Phase.PLACING_BOMBS) {
            deny(p, "Ahora no se pueden enterrar papas.");
            return;
        }
        if (idx != turn) {
            deny(p, "No es tu turno. Le toca a " + name(turn) + ".");
            return;
        }
        long key = pos.asLong();
        if (!board.contains(key)) {
            deny(p, "Eso está fuera del tablero de esta ronda.");
            return;
        }
        if (stones.contains(key)) {
            deny(p, "No se pueden enterrar papas en piedra.");
            return;
        }
        if (bombs.containsKey(key)) {
            deny(p, "Ahí ya hay una papa enterrada.");
            return;
        }
        if (remaining[idx] <= 0) {
            deny(p, "Ya enterraste todas tus papas.");
            return;
        }

        ItemStack held = p.getItemInHand(hand);
        if (!p.getAbilities().instabuild) held.shrink(1);
        bombs.put(key, p.getUUID());
        remaining[idx]--;

        // Animación visible para ambos: tierra + humo + papa que se hunde
        double x = pos.getX() + 0.5, y = pos.getY() + 1.0, z = pos.getZ() + 0.5;
        Fx.particles(level, new BlockParticleOption(ParticleTypes.BLOCK, DIRT), x, y, z, 30, 0.3, 0.1);
        Fx.particles(level, ParticleTypes.POOF, x, y + 0.2, z, 8, 0.2, 0.02);
        Fx.sound(level, pos, SoundEvents.ROOTED_DIRT_PLACE, 1.0f, 0.8f);
        Fx.sound(level, pos, SoundEvents.GRAVEL_BREAK, 0.8f, 0.7f);
        ItemEntity potato = floatingPotato(x, y + 0.6, z, -0.03);
        schedule(25, () -> {
            Fx.particles(level, ParticleTypes.SMOKE, x, y + 0.1, z, 10, 0.15, 0.01);
            potato.discard();
            tempEntities.remove(potato);
        });

        broadcast(msg(name(idx) + " enterró una Papa Bomba. Faltan: " + name(0) + " " + remaining[0]
                + " · " + name(1) + " " + remaining[1], ChatFormatting.YELLOW));

        if (remaining[0] == 0 && remaining[1] == 0) {
            phase = Phase.ROUND_TRANSITION;
            schedule(35, GameManager::startDigging);
            return;
        }
        // Cada jugador entierra TODAS sus papas en su turno; luego pasa al otro
        if (remaining[idx] > 0) return;
        turn = 1 - idx;
        schedule(15, GameManager::announceTurn);
    }

    // ------------------------------------------------------------------ fase 2: excavar

    private static void dig(ServerPlayer p, BlockPos pos) {
        int idx = players.indexOf(p.getUUID());
        if (phase != Phase.DIGGING) {
            deny(p, phase == Phase.PLACING_BOMBS ? "Todavía se están enterrando papas." : "Espera…");
            return;
        }
        if (idx != turn) {
            deny(p, "No es tu turno. Le toca a " + name(turn) + ".");
            return;
        }
        long key = pos.asLong();
        if (!board.contains(key) || dug.contains(key)) return;
        if (stones.contains(key)) {
            deny(p, "La piedra no se excava.");
            return;
        }
        if (!GameItems.is(p.getMainHandItem(), GameItems.SHOVEL)) {
            deny(p, "Usa la Pala de Papa Caliente para excavar.");
            return;
        }

        double x = pos.getX() + 0.5, y = pos.getY() + 0.5, z = pos.getZ() + 0.5;
        dug.add(key);
        level.setBlockAndUpdate(pos, AIR);

        if (bombs.containsKey(key)) {
            bombs.remove(key);
            Fx.particles(level, ParticleTypes.EXPLOSION_EMITTER, x, y, z, 1, 0, 0);
            Fx.particles(level, ParticleTypes.FLAME, x, y, z, 60, 0.6, 0.15);
            Fx.particles(level, ParticleTypes.LARGE_SMOKE, x, y, z, 30, 0.5, 0.05);
            Fx.sound(level, pos, SoundEvents.DRAGON_FIREBALL_EXPLODE, 2.0f, 0.9f);
            Fx.sound(level, pos, SoundEvents.TNT_PRIMED, 1.0f, 1.5f);
            lose(idx, name(idx) + " desenterró una Papa Bomba. ¡BOOM!");
            return;
        }

        Fx.particles(level, new BlockParticleOption(ParticleTypes.BLOCK, DIRT), x, y, z, 25, 0.3, 0.1);
        Fx.particles(level, ParticleTypes.HAPPY_VILLAGER, x, y + 0.3, z, 8, 0.3, 0);
        Fx.sound(level, pos, SoundEvents.GRAVEL_BREAK, 1.0f, 1.0f);
        Fx.sound(level, pos, SoundEvents.AMETHYST_BLOCK_CHIME, 1.0f, 1.4f);
        broadcast(msg(name(idx) + " cavó un bloque: ¡a salvo!", ChatFormatting.GREEN));
        turn = 1 - idx;
        schedule(5, GameManager::announceTurn);
    }

    /** @return true si se acepta la pulsación (el botón vanilla se activa). */
    private static boolean pressButton(ServerPlayer p) {
        int idx = players.indexOf(p.getUUID());
        if (idx < 0) {
            deny(p, "Solo los jugadores de la partida pueden usar el botón.");
            return false;
        }
        if (phase != Phase.DIGGING) {
            deny(p, phase == Phase.PLACING_BOMBS ? "El botón solo se usa en la fase de excavación." : "Espera…");
            return false;
        }
        if (idx != turn) {
            deny(p, "No es tu turno. Le toca a " + name(turn) + ".");
            return false;
        }

        List<BlockPos> safeLeft = new ArrayList<>();
        for (long key : board) {
            if (!stones.contains(key) && !dug.contains(key) && !bombs.containsKey(key)) safeLeft.add(BlockPos.of(key));
        }
        broadcast(msg(name(idx) + " pulsó el botón: «¡Tablero limpio!»", ChatFormatting.LIGHT_PURPLE));

        if (safeLeft.isEmpty()) {
            roundCleared();
        } else {
            DustParticleOptions green = new DustParticleOptions(new Vector3f(0.2f, 1.0f, 0.2f), 1.5f);
            for (BlockPos s : safeLeft) {
                Fx.particles(level, green, s.getX() + 0.5, s.getY() + 1.1, s.getZ() + 0.5, 15, 0.3, 0);
            }
            Fx.sound(level, buttonPos, SoundEvents.VILLAGER_NO, 1.0f, 0.9f);
            lose(idx, name(idx) + " pulsó el botón antes de tiempo: quedaban " + safeLeft.size()
                    + " bloque(s) de tierra sin bomba (marcados en verde).");
        }
        return true;
    }

    // ------------------------------------------------------------------ resultados

    private static void roundCleared() {
        phase = Phase.ROUND_TRANSITION;
        revealBombs();
        BlockPos center = Board.cell(origin, forward, Board.MAX_SIZE / 2, Board.MAX_SIZE / 2);
        Fx.sound(level, center, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
        boolean last = round >= Board.MAX_ROUND;
        for (UUID id : players) {
            ServerPlayer p = player(id);
            if (p == null) continue;
            Fx.title(p, Component.literal("¡Ronda superada!").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD),
                    Component.literal(last ? "Era el tablero " + size + "x" + size + "…" : "Siguiente: " + (size + 1) + "x" + (size + 1))
                            .withStyle(ChatFormatting.YELLOW), 5, 50, 10);
        }
        broadcast(msg("¡Correcto! Toda la tierra restante tenía bombas. Ambos superan la ronda " + round + ".", ChatFormatting.GREEN));
        if (last) {
            schedule(60, GameManager::gameWon);
        } else {
            int next = round + 1;
            schedule(NEXT_ROUND_TICKS, () -> beginRound(next));
        }
    }

    private static void gameWon() {
        phase = Phase.GAME_OVER;
        BlockPos center = Board.cell(origin, forward, Board.MAX_SIZE / 2, Board.MAX_SIZE / 2);
        Fx.particles(level, ParticleTypes.TOTEM_OF_UNDYING, center.getX() + 0.5, center.getY() + 1.5, center.getZ() + 0.5, 150, 2.0, 0.5);
        Fx.sound(level, center, SoundEvents.PLAYER_LEVELUP, 1.0f, 0.6f);
        for (UUID id : players) {
            ServerPlayer p = player(id);
            if (p == null) continue;
            Fx.title(p, Component.literal("¡GANARON!").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD),
                    Component.literal("Superaron las " + Board.MAX_ROUND + " rondas de Papa Caliente").withStyle(ChatFormatting.YELLOW), 10, 80, 20);
        }
        broadcast(msg("¡" + name(0) + " y " + name(1) + " completaron el tablero " + Board.MAX_SIZE + "x" + Board.MAX_SIZE + " y ganan el juego!", ChatFormatting.GOLD));
        schedule(GAME_OVER_TICKS, GameManager::finish);
    }

    private static void lose(int loserIdx, String reason) {
        phase = Phase.GAME_OVER;
        revealBombs();
        int winnerIdx = 1 - loserIdx;
        ServerPlayer loser = player(players.get(loserIdx));
        ServerPlayer winner = player(players.get(winnerIdx));
        if (loser != null) {
            Fx.title(loser, Component.literal("¡PERDISTE!").withStyle(ChatFormatting.RED, ChatFormatting.BOLD),
                    Component.literal(reason).withStyle(ChatFormatting.GRAY), 5, 80, 20);
        }
        if (winner != null) {
            Fx.title(winner, Component.literal("¡GANASTE!").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD),
                    Component.literal(reason).withStyle(ChatFormatting.GRAY), 5, 80, 20);
            Fx.soundTo(winner, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
        }
        broadcast(msg(reason + " Gana " + name(winnerIdx) + " (ronda " + round + ").", ChatFormatting.RED));
        schedule(GAME_OVER_TICKS, GameManager::finish);
    }

    /** Fin normal: restaura la arena y vuelve a la sala con los mismos jugadores (revancha con /start). */
    private static void finish() {
        cleanupArena();
        phase = Phase.LOBBY;
        broadcast(msg("Partida terminada. Usa /papacaliente start para la revancha o /papacaliente salir.", ChatFormatting.GOLD));
    }

    /** Un jugador abandona/desconecta: gana el otro automáticamente. */
    private static void forfeit(UUID leaving, String reason) {
        if (phase == Phase.LOBBY) return;
        int idx = players.indexOf(leaving);
        if (idx < 0) return;
        boolean alreadyOver = phase == Phase.GAME_OVER;
        generation++;
        tasks.clear();
        cleanupArena();
        phase = Phase.LOBBY;
        if (!alreadyOver) {
            ServerPlayer winner = player(players.get(1 - idx));
            if (winner != null) {
                Fx.title(winner, Component.literal("¡GANASTE!").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD),
                        Component.literal(reason).withStyle(ChatFormatting.GRAY), 5, 60, 15);
            }
            broadcast(msg(reason + " Gana " + name(1 - idx) + ".", ChatFormatting.RED));
        }
    }

    public static void onDisconnect(ServerPlayer p) {
        SelectionManager.cancel(p);
        if (!players.contains(p.getUUID())) return;
        GameItems.removeAll(p);
        if (phase == Phase.LOBBY) {
            players.remove(p.getUUID());
            broadcast(msg(p.getName().getString() + " se desconectó y salió de la sala.", ChatFormatting.YELLOW));
            return;
        }
        forfeit(p.getUUID(), p.getName().getString() + " se desconectó.");
        players.remove(p.getUUID());
    }

    private static void revealBombs() {
        DustParticleOptions red = new DustParticleOptions(new Vector3f(1.0f, 0.1f, 0.1f), 1.5f);
        for (long key : bombs.keySet()) {
            BlockPos b = BlockPos.of(key);
            double x = b.getX() + 0.5, y = b.getY() + 1.0, z = b.getZ() + 0.5;
            Fx.particles(level, red, x, y + 0.2, z, 20, 0.25, 0);
            Fx.particles(level, ParticleTypes.FLAME, x, y + 0.1, z, 10, 0.2, 0.02);
            floatingPotato(x, y + 0.4, z, 0);
        }
        if (!bombs.isEmpty()) {
            Fx.sound(level, BlockPos.of(bombs.keySet().iterator().next()), SoundEvents.TNT_PRIMED, 0.8f, 1.2f);
        }
    }

    private static void cleanupArena() {
        clearTempEntities();
        for (UUID id : players) {
            ServerPlayer p = player(id);
            if (p != null) GameItems.removeAll(p);
        }
        if (level != null) {
            for (Map.Entry<Long, BlockState> e : snapshot.entrySet()) {
                level.setBlockAndUpdate(BlockPos.of(e.getKey()), e.getValue());
            }
        }
        snapshot.clear();
        bombs.clear();
        stones.clear();
        dug.clear();
        board.clear();
        allCells.clear();
    }

    private static ItemEntity floatingPotato(double x, double y, double z, double vy) {
        ItemStack stack = new ItemStack(Items.POTATO);
        stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        ItemEntity e = new ItemEntity(level, x, y, z, stack);
        e.setNeverPickUp();
        e.setNoGravity(true);
        e.setUnlimitedLifetime();
        e.setDeltaMovement(0, vy, 0);
        level.addFreshEntity(e);
        tempEntities.add(e);
        return e;
    }

    private static void clearTempEntities() {
        for (Entity e : tempEntities) e.discard();
        tempEntities.clear();
    }

    // ------------------------------------------------------------------ HUD

    private static void hud() {
        for (int idx = 0; idx < players.size(); idx++) {
            ServerPlayer p = player(players.get(idx));
            if (p == null) continue;
            MutableComponent bar = Component.literal("Ronda " + round + "/" + Board.MAX_ROUND + " (" + size + "x" + size + ")")
                    .withStyle(ChatFormatting.GOLD);
            bar.append(Component.literal("  ·  " + phaseName()).withStyle(ChatFormatting.AQUA));
            if (phase == Phase.PLACING_BOMBS || phase == Phase.DIGGING) {
                boolean mine = idx == turn;
                bar.append(Component.literal("  ·  " + (mine ? "¡TU TURNO!" : "Turno de " + name(turn)))
                        .withStyle(mine ? ChatFormatting.GREEN : ChatFormatting.GRAY));
            }
            if (phase == Phase.PLACING_BOMBS) {
                bar.append(Component.literal("  ·  Te quedan " + remaining[idx] + " papa(s)").withStyle(ChatFormatting.YELLOW));
            }
            Fx.actionBar(p, bar);
        }
    }

    /** Repone ítems si alguien los tiró o perdió. */
    private static void ensureItems() {
        for (int idx = 0; idx < players.size(); idx++) {
            ServerPlayer p = player(players.get(idx));
            if (p == null) continue;
            if (phase == Phase.PLACING_BOMBS) {
                int have = GameItems.count(p, GameItems.BOMB);
                if (have < remaining[idx]) GameItems.give(p, GameItems.bomb(remaining[idx] - have));
            } else if (phase == Phase.DIGGING) {
                if (GameItems.count(p, GameItems.SHOVEL) == 0) GameItems.give(p, GameItems.shovel());
            }
        }
    }

    private static String phaseName() {
        return switch (phase) {
            case LOBBY -> "Sala de espera";
            case PLACING_BOMBS -> "Enterrando papas";
            case DIGGING -> "Excavando";
            case ROUND_TRANSITION -> "Preparando tablero";
            case GAME_OVER -> "Fin de la partida";
        };
    }

    // ------------------------------------------------------------------ eventos de interacción

    /** Región protegida: área máxima del tablero, desde una capa debajo hasta dos encima, más el botón y su soporte. */
    public static boolean isProtected(Level lvl, BlockPos pos) {
        if (phase == Phase.LOBBY) return false;
        if (lvl == buttonLevel && buttonPos != null && pos.distManhattan(buttonPos) <= 1) return true;
        if (lvl != level) return false;
        int dy = pos.getY() - origin.getY();
        if (dy < -1 || dy > 2) return false;
        return allCells.contains(new BlockPos(pos.getX(), origin.getY(), pos.getZ()).asLong());
    }

    private static boolean inArenaVolume(BlockPos pos) {
        int dy = pos.getY() - origin.getY();
        if (dy < 0 || dy > 2) return false;
        return allCells.contains(new BlockPos(pos.getX(), origin.getY(), pos.getZ()).asLong());
    }

    private static boolean isBoardSurface(BlockPos pos) {
        return pos.getY() == origin.getY() && board.contains(pos.asLong());
    }

    /** Clic izquierdo sobre un bloque (servidor). */
    public static InteractionResult onAttackBlock(ServerPlayer p, BlockPos pos) {
        if (!isProtected(p.level(), pos)) {
            if (GameItems.is(p.getMainHandItem(), GameItems.SHOVEL)) return InteractionResult.FAIL;
            return InteractionResult.PASS;
        }
        if (isParticipant(p) && level == p.level() && isBoardSurface(pos)) {
            dig(p, pos);
        } else {
            Fx.actionBar(p, Component.literal("La arena de Papa Caliente está protegida.").withStyle(ChatFormatting.RED));
        }
        return InteractionResult.FAIL;
    }

    /** Clic derecho sobre un bloque (servidor). */
    public static InteractionResult onUseBlock(ServerPlayer p, InteractionHand hand, BlockHitResult hit) {
        BlockPos pos = hit.getBlockPos();
        ItemStack stack = p.getItemInHand(hand);
        if (phase == Phase.LOBBY) {
            return GameItems.is(stack, GameItems.BOMB) ? InteractionResult.FAIL : InteractionResult.PASS;
        }
        if (p.level() == buttonLevel && pos.equals(buttonPos)) {
            if (hand != InteractionHand.MAIN_HAND) return InteractionResult.FAIL;
            return pressButton(p) ? InteractionResult.PASS : InteractionResult.FAIL;
        }
        if (isProtected(p.level(), pos) || isProtected(p.level(), pos.relative(hit.getDirection()))) {
            if (hand == InteractionHand.MAIN_HAND && isParticipant(p) && level == p.level()
                    && GameItems.is(stack, GameItems.BOMB) && isBoardSurface(pos)) {
                bury(p, hand, pos);
                return InteractionResult.SUCCESS;
            }
            return InteractionResult.FAIL;
        }
        return GameItems.is(stack, GameItems.BOMB) ? InteractionResult.FAIL : InteractionResult.PASS;
    }

    // ------------------------------------------------------------------ utilidades

    private static void deny(ServerPlayer p, String text) {
        Fx.actionBar(p, Component.literal(text).withStyle(ChatFormatting.RED));
        Fx.soundTo(p, SoundEvents.VILLAGER_NO, 0.6f, 1.2f);
    }

    private static ServerPlayer player(UUID id) {
        return server == null ? null : server.getPlayerList().getPlayer(id);
    }

    private static String name(int idx) {
        if (idx < 0 || idx >= players.size()) return "?";
        return names.getOrDefault(players.get(idx), "?");
    }

    private static ServerLevel levelById(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null || server == null) return null;
        return server.getLevel(ResourceKey.create(Registries.DIMENSION, rl));
    }

    public static MutableComponent msg(String text, ChatFormatting color) {
        return Component.literal("[Papa Caliente] ").withStyle(ChatFormatting.GOLD)
                .append(Component.literal(text).withStyle(color));
    }

    private static void broadcast(Component c) {
        for (UUID id : players) {
            ServerPlayer p = player(id);
            if (p != null) p.sendSystemMessage(c);
        }
    }
}
