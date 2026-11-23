package com.shaw.papacaliente;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PapaCaliente implements ModInitializer {
    public static final String MOD_ID = "papa_caliente";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        ModBlocks.init();
        ModParticles.init();
        PayloadTypeRegistry.playS2C().register(SelectionPayload.TYPE, SelectionPayload.CODEC);
        ArenaConfig.load();

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> registerCommands(dispatcher));

        ServerLifecycleEvents.SERVER_STARTED.register(GameManager::init);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> GameManager.shutdown());
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            SelectionManager.tick(server);
            GameManager.tick();
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> GameManager.onDisconnect(handler.player));

        // Clic izquierdo: selección de arena/botón o excavar
        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
            if (world.isClientSide() || !(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
            if (SelectionManager.isSelecting(sp)) {
                SelectionManager.handleClick(sp, pos);
                return InteractionResult.FAIL;
            }
            return GameManager.onAttackBlock(sp, pos);
        });

        // Clic derecho: selección, enterrar papas, botón de "tablero limpio"
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (world.isClientSide() || !(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
            if (SelectionManager.isSelecting(sp)) {
                SelectionManager.handleClick(sp, hit.getBlockPos());
                return InteractionResult.FAIL;
            }
            return GameManager.onUseBlock(sp, hand, hit);
        });

        // Evitar comerse la Papa Bomba
        UseItemCallback.EVENT.register((player, world, hand) -> {
            ItemStack stack = player.getItemInHand(hand);
            if (GameItems.is(stack, GameItems.BOMB)) return InteractionResultHolder.fail(stack);
            return InteractionResultHolder.pass(stack);
        });

        // Protección de la arena contra roturas (manos, pistones de jugadores en creativo, etc.)
        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) -> {
            if (GameManager.isProtected(world, pos)) return false;
            return !GameItems.is(player.getMainHandItem(), GameItems.SHOVEL);
        });

        LOGGER.info("Papa Caliente cargado.");
    }

    private static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("papacaliente")
                .executes(ctx -> {
                    ctx.getSource().sendSuccess(GameManager::status, false);
                    return 1;
                })
                .then(Commands.literal("setarena")
                        .requires(src -> src.hasPermission(2))
                        .executes(ctx -> {
                            ServerPlayer p = ctx.getSource().getPlayerOrException();
                            if (GameManager.isActive()) return fail(ctx, "No se puede cambiar la arena durante una partida.");
                            SelectionManager.start(p);
                            return 1;
                        }))
                .then(Commands.literal("cancelar")
                        .executes(ctx -> {
                            ServerPlayer p = ctx.getSource().getPlayerOrException();
                            boolean was = SelectionManager.cancel(p);
                            p.sendSystemMessage(GameManager.msg(was ? "Modo selección cancelado." : "No estabas en modo selección.",
                                    ChatFormatting.GRAY));
                            return 1;
                        }))
                .then(Commands.literal("join")
                        .executes(ctx -> {
                            GameManager.join(ctx.getSource().getPlayerOrException());
                            return 1;
                        }))
                .then(Commands.literal("salir")
                        .executes(ctx -> {
                            GameManager.leave(ctx.getSource().getPlayerOrException());
                            return 1;
                        }))
                .then(Commands.literal("start")
                        .executes(ctx -> {
                            String error = GameManager.start();
                            if (error != null) return fail(ctx, error);
                            return 1;
                        }))
                .then(Commands.literal("stop")
                        .executes(ctx -> {
                            if (!ctx.getSource().hasPermission(2) && !isParticipant(ctx.getSource()))
                                return fail(ctx, "Solo un jugador de la partida o un operador puede detenerla.");
                            if (!GameManager.isActive()) return fail(ctx, "No hay ninguna partida en curso.");
                            GameManager.stop("Partida detenida por " + ctx.getSource().getTextName() + ".");
                            ctx.getSource().sendSuccess(() -> GameManager.msg("Partida detenida y arena restaurada.",
                                    ChatFormatting.YELLOW), true);
                            return 1;
                        }))
                .then(Commands.literal("estado")
                        .executes(ctx -> {
                            ctx.getSource().sendSuccess(GameManager::status, false);
                            return 1;
                        })));
    }

    private static boolean isParticipant(CommandSourceStack src) {
        ServerPlayer p = src.getPlayer();
        return p != null && GameManager.isParticipant(p);
    }

    private static int fail(CommandContext<CommandSourceStack> ctx, String text) throws CommandSyntaxException {
        ctx.getSource().sendFailure(Component.literal("[Papa Caliente] " + text));
        return 0;
    }
}
