package com.shaw.papacaliente.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.shaw.papacaliente.Board;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Vista previa "limpia" de la arena, dibujada en el cliente: un plano translúcido con cuadrícula fina,
 * el 3x3 inicial resaltado en verde y la posición del botón en celeste. Late suavemente.
 */
public final class SelectionRenderer {
    private static final double REACH = 64.0;
    private static boolean active = false;

    private SelectionRenderer() {
    }

    public static void setActive(boolean value) {
        active = value;
    }

    public static void render(WorldRenderContext ctx) {
        if (!active) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) return;
        MultiBufferSource consumers = ctx.consumers();
        PoseStack ps = ctx.matrixStack();
        if (consumers == null || ps == null) return;

        HitResult hit = player.pick(REACH, 1.0f, false);
        if (hit.getType() != HitResult.Type.BLOCK || !(hit instanceof BlockHitResult bhr)) return;

        BlockPos origin = bhr.getBlockPos();
        Direction forward = player.getDirection();

        // Rectángulos en coordenadas de mundo (x0, z0, x1, z1)
        double[] area = rect(origin, forward, 0, Board.MAX_SIZE);
        double[] start = rect(origin, forward, Board.offset(Board.MIN_SIZE), Board.MIN_SIZE);
        BlockPos button = Board.buttonPos(origin, forward);

        // Trabajamos en coordenadas relativas a la esquina del área para no perder precisión
        double bx = area[0], by = origin.getY() + 1.0, bz = area[1];
        Vec3 cam = ctx.camera().getPosition();
        float pulse = 0.8f + 0.2f * (float) Math.sin((System.currentTimeMillis() % 100_000L) / 220.0);

        ps.pushPose();
        ps.translate(bx - cam.x, by - cam.y, bz - cam.z);

        double w = area[2] - area[0], d = area[3] - area[1];
        double sx0 = start[0] - bx, sz0 = start[1] - bz, sx1 = start[2] - bx, sz1 = start[3] - bz;
        double ux = button.getX() - bx, uy = button.getY() - by, uz = button.getZ() - bz;

        // 1) Rellenos translúcidos
        VertexConsumer fill = consumers.getBuffer(RenderType.debugFilledBox());
        LevelRenderer.addChainedFilledBoxVertices(ps, fill, 0, 0.004, 0, w, 0.006, d,
                1.0f, 1.0f, 1.0f, 0.10f * pulse);
        LevelRenderer.addChainedFilledBoxVertices(ps, fill, sx0, 0.008, sz0, sx1, 0.010, sz1,
                0.35f, 1.0f, 0.45f, 0.30f * pulse);
        LevelRenderer.addChainedFilledBoxVertices(ps, fill, ux + 0.3, uy, uz + 0.3, ux + 0.7, uy + 0.15, uz + 0.7,
                0.30f, 0.85f, 1.0f, 0.45f * pulse);
        endBatch(consumers, RenderType.debugFilledBox());

        // 2) Líneas: borde, cuadrícula fina, 3x3 inicial y botón
        VertexConsumer lines = consumers.getBuffer(RenderType.lines());
        double ly = 0.012;
        for (int k = 1; k < Board.MAX_SIZE; k++) {
            line(ps, lines, k, ly, 0, k, ly, d, 1f, 1f, 1f, 0.18f);
            line(ps, lines, 0, ly, k, w, ly, k, 1f, 1f, 1f, 0.18f);
        }
        LevelRenderer.renderLineBox(ps, lines, new AABB(0, ly, 0, w, ly, d), 1f, 1f, 1f, 0.95f);
        LevelRenderer.renderLineBox(ps, lines, new AABB(sx0, ly + 0.002, sz0, sx1, ly + 0.002, sz1),
                0.35f, 1.0f, 0.45f, 1.0f);
        LevelRenderer.renderLineBox(ps, lines, new AABB(ux + 0.3, uy, uz + 0.3, ux + 0.7, uy + 0.15, uz + 0.7),
                0.30f, 0.85f, 1.0f, 1.0f);
        endBatch(consumers, RenderType.lines());

        ps.popPose();
    }

    private static double[] rect(BlockPos origin, Direction forward, int off, int size) {
        BlockPos a = Board.cell(origin, forward, off, off);
        BlockPos b = Board.cell(origin, forward, off + size - 1, off + size - 1);
        return new double[]{
                Math.min(a.getX(), b.getX()), Math.min(a.getZ(), b.getZ()),
                Math.max(a.getX(), b.getX()) + 1, Math.max(a.getZ(), b.getZ()) + 1};
    }

    private static void line(PoseStack ps, VertexConsumer vc, double x0, double y0, double z0,
                             double x1, double y1, double z1, float r, float g, float b, float a) {
        PoseStack.Pose pose = ps.last();
        float nx = (float) (x1 - x0), nz = (float) (z1 - z0);
        float len = (float) Math.sqrt(nx * nx + nz * nz);
        if (len > 0) {
            nx /= len;
            nz /= len;
        }
        vc.addVertex(pose, (float) x0, (float) y0, (float) z0).setColor(r, g, b, a).setNormal(pose, nx, 0f, nz);
        vc.addVertex(pose, (float) x1, (float) y1, (float) z1).setColor(r, g, b, a).setNormal(pose, nx, 0f, nz);
    }

    private static void endBatch(MultiBufferSource consumers, RenderType type) {
        if (consumers instanceof MultiBufferSource.BufferSource source) source.endBatch(type);
    }
}
