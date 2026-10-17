package dervogel101.de.emulator_i86_crafted.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dervogel101.de.emulator_i86_crafted.ModContent;
import dervogel101.de.emulator_i86_crafted.block.MachineBlock;
import dervogel101.de.emulator_i86_crafted.block.PortBlock;
import dervogel101.de.emulator_i86_crafted.network.IndicatorPackets;
import dervogel101.de.i86.runtime.Hardware;
import dervogel101.de.i86.runtime.IndicatorState;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import org.joml.Vector3f;
import java.util.*;

/** One batched emissive pass. Only immutable, camera-relative faces enter the draw phase. */
public final class ClientIndicators {
    private static final Map<BlockPos, IndicatorState.Lamps> LAMPS = new HashMap<>();
    private static final RenderStateDataKey<List<Face>> FACES = RenderStateDataKey.create(() -> "i86 indicators");
    private static ClientLevel level;
    private static final int[] STATUS = {0, 0x00ff40, 0xffa000, 0xff0000};
    private static final int[] ACTIVITY = {0x00ffff, 0xffa000, 0xc000ff};
    private record Face(Vector3f a, Vector3f b, Vector3f c, Vector3f d, int color) {}
    private ClientIndicators() {}

    public static void initialize() {
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> { LAMPS.clear(); level = null; });
        ClientPlayNetworking.registerGlobalReceiver(IndicatorPackets.Update.TYPE, (packet, context) -> {
            var current = context.client().level;
            if (current == null || !current.dimension().identifier().toString().equals(packet.dimension())) return;
            if (level != current) { LAMPS.clear(); level = current; }
            long now = System.nanoTime();
            for (var entry : packet.entries()) {
                if (entry.flags() == IndicatorState.REMOVE) LAMPS.remove(entry.position());
                else LAMPS.computeIfAbsent(entry.position(), ignored -> new IndicatorState.Lamps()).update(entry.flags(), now);
            }
        });
        LevelExtractionEvents.END_EXTRACTION.register(context -> {
            if (level != context.level()) { LAMPS.clear(); level = context.level(); }
            var faces = new ArrayList<Face>();
            var camera = context.levelState().cameraRenderState; long now = System.nanoTime();
            for (var entry : LAMPS.entrySet()) {
                var pos = entry.getKey();
                if (!level.hasChunkAt(pos) || camera.pos.distanceToSqr(pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5) > 128 * 128
                        || !camera.cullFrustum.isVisible(new AABB(pos))) continue;
                var state = level.getBlockState(pos);
                if (!(state.getBlock() instanceof MachineBlock block) || block == ModContent.BLOCKS.get("super_structure")) continue;
                Direction facing = state.getValue(block instanceof PortBlock ? PortBlock.PORT_FACING : MachineBlock.FACING);
                boolean sign = block == ModContent.BLOCKS.get("sign_port");
                var lamps = entry.getValue();
                for (int slot = 0; slot < 4; slot++) {
                    if (block.hardware() == Hardware.CONTROLLER && slot == 1) continue;
                    if (slot == 1 && (block.hardware() == Hardware.RESET || block.hardware() == Hardware.NMI || block.hardware() == Hardware.TEST)) continue;
                    float brightness = lamps.brightness(slot, now); if (brightness <= 0) continue;
                    int color = slot == 0 ? STATUS[lamps.status()] : block.hardware() == Hardware.CONTROLLER
                            ? ACTIVITY[slot - 2] : ACTIVITY[slot - 1];
                    int argb = (Math.clamp(Math.round(brightness * 255), 0, 255) << 24) | color;
                    float x = sign ? 7.2f + slot * .8f : 5.4f - slot * .8f;
                    float y = sign ? 2.1f : 2.4f;
                    float width = sign ? -.8f : .8f;
                    faces.add(new Face(point(x, y, facing, sign, pos, camera.pos.x, camera.pos.y, camera.pos.z),
                            point(x, y + .8f, facing, sign, pos, camera.pos.x, camera.pos.y, camera.pos.z),
                            point(x + width, y + .8f, facing, sign, pos, camera.pos.x, camera.pos.y, camera.pos.z),
                            point(x + width, y, facing, sign, pos, camera.pos.x, camera.pos.y, camera.pos.z), argb));
                }
            }
            context.levelState().setData(FACES, List.copyOf(faces));
        });
        LevelRenderEvents.COLLECT_SUBMITS.register(context -> {
            var faces = context.levelState().getData(FACES);
            if (faces == null || faces.isEmpty()) return;
            context.submitNodeCollector().submitCustomGeometry(context.poseStack(),
                    RenderTypes.entityTranslucentEmissive(ModContent.id("textures/block/led.png")), (pose, vertices) -> {
                        for (var face : faces) {
                            vertex(vertices, pose, face.a(), face.color(), 0, 1);
                            vertex(vertices, pose, face.b(), face.color(), 0, 0);
                            vertex(vertices, pose, face.c(), face.color(), 1, 0);
                            vertex(vertices, pose, face.d(), face.color(), 1, 1);
                        }
                    });
        });
    }

    private static Vector3f point(float x, float y, Direction facing, boolean sign, BlockPos pos, double cx, double cy, double cz) {
        var v = new Vector3f(x / 16 - .5f, y / 16 - .5f, (sign ? 15.96f : .25f) / 16 - .5f);
        if (facing.getAxis().isHorizontal()) {
            int turns = switch (facing) { case EAST -> 1; case SOUTH -> 2; case WEST -> 3; default -> 0; };
            if (sign) turns += 2;
            v.rotateY((float) (-turns * Math.PI / 2));
        } else if (sign) v.rotateX((float) (facing == Direction.UP ? -Math.PI / 2 : Math.PI / 2));
        else v.rotateX((float) (facing == Direction.UP ? Math.PI / 2 : -Math.PI / 2));
        return v.add((float) (pos.getX() + .5 - cx), (float) (pos.getY() + .5 - cy), (float) (pos.getZ() + .5 - cz));
    }
    private static void vertex(VertexConsumer vertices, PoseStack.Pose pose, Vector3f position, int color, float u, float v) {
        vertices.addVertex(pose, position).setColor(color).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY).setLight(0xf000f0).setNormal(pose, 0, 0, -1);
    }
}
