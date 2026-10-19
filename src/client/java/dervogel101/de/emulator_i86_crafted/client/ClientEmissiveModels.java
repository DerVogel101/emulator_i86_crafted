package dervogel101.de.emulator_i86_crafted.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dervogel101.de.emulator_i86_crafted.Emulator_i86_crafted;
import dervogel101.de.emulator_i86_crafted.ModContent;
import dervogel101.de.emulator_i86_crafted.block.MachineBlock;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelModifier;
import net.fabricmc.fabric.api.client.model.loading.v1.wrapper.WrapperBlockStateModel;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.renderer.v1.Renderer;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.Mesh;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadAtlas;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadView;
import net.fabricmc.fabric.api.client.renderer.v1.sprite.SpriteFinder;
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.*;
import java.util.function.Predicate;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/** Baked luminous faces use the same standard emissive pipeline as the activity LEDs. */
public final class ClientEmissiveModels {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(Emulator_i86_crafted.MOD_ID);
    private static final Map<Long, Set<BlockPos>> MACHINES = new HashMap<>();
    private static final RenderStateDataKey<List<Draw>> DRAWS = RenderStateDataKey.create(() -> "i86 emissive models");
    private static ClientLevel level;
    private static MethodHandle activateSprite = spriteActivation();
    private record Draw(float x, float y, float z, Mesh mesh) {}

    private ClientEmissiveModels() {}

    public static void initialize() {
        ModelLoadingPlugin.register(plugin -> plugin.modifyBlockModelAfterBake().register(ModelModifier.WRAP_LAST_PHASE, (model, context) ->
                hasLuminousModel(context.state())
                        ? new EmissiveModel(model, context.baker().materials().spriteFinder(QuadAtlas.BLOCK)) : model));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> { MACHINES.clear(); level = null; });
        ClientChunkEvents.CHUNK_LOAD.register((world, chunk) -> {
            useLevel(world);
            var positions = new HashSet<BlockPos>();
            var sections = chunk.getSections();
            int baseX = chunk.getPos().getMinBlockX(), baseZ = chunk.getPos().getMinBlockZ();
            for (int section = 0; section < sections.length; section++) {
                var data = sections[section];
                if (!data.maybeHas(ClientEmissiveModels::hasLuminousModel)) continue;
                for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                    if (hasLuminousModel(data.getBlockState(x, y, z)))
                        positions.add(new BlockPos(baseX + x, world.getMinY() + section * 16 + y, baseZ + z));
                }
            }
            if (positions.isEmpty()) MACHINES.remove(chunk.getPos().pack());
            else MACHINES.put(chunk.getPos().pack(), positions);
        });
        ClientChunkEvents.CHUNK_UNLOAD.register((world, chunk) -> {
            if (world == level) MACHINES.remove(chunk.getPos().pack());
        });
        LevelExtractionEvents.END_EXTRACTION.register(context -> {
            useLevel(context.level());
            var draws = new ArrayList<Draw>();
            var camera = context.levelState().cameraRenderState;
            var models = Minecraft.getInstance().getModelManager().getBlockStateModelSet();
            for (var positions : MACHINES.values()) for (var pos : positions) {
                if (!level.hasChunkAt(pos) || !camera.cullFrustum.isVisible(new AABB(pos))) continue;
                var state = level.getBlockState(pos);
                if (hasLuminousModel(state) && (state.getValue(MachineBlock.POWERED) || state.getValue(MachineBlock.ACTIVE))
                        && models.get(state) instanceof EmissiveModel model) {
                    var mesh = model.glowingMesh(level, pos, state);
                    if (mesh.size() > 0) draws.add(new Draw((float) (pos.getX() - camera.pos.x),
                            (float) (pos.getY() - camera.pos.y), (float) (pos.getZ() - camera.pos.z), mesh));
                }
            }
            context.levelState().setData(DRAWS, List.copyOf(draws));
        });
        LevelRenderEvents.COLLECT_SUBMITS.register(context -> {
            var draws = context.levelState().getData(DRAWS);
            if (draws == null || draws.isEmpty()) return;
            context.submitNodeCollector().submitCustomGeometry(context.poseStack(),
                    RenderTypes.entityTranslucentEmissive(QuadAtlas.BLOCK.getTextureLocation()), (pose, vertices) -> {
                        var stack = new PoseStack();
                        stack.last().pose().set(pose.pose());
                        stack.last().normal().set(pose.normal());
                        for (var draw : draws) {
                            stack.pushPose();
                            stack.translate(draw.x(), draw.y(), draw.z());
                            draw.mesh().forEach(quad -> quad.buffer(OverlayTexture.NO_OVERLAY, stack.last(), vertices));
                            stack.popPose();
                        }
                    });
        });
    }

    public static void blockChanged(ClientLevel world, BlockPos pos, BlockState state) {
        useLevel(world);
        long chunk = ChunkPos.pack(pos.getX() >> 4, pos.getZ() >> 4);
        if (hasLuminousModel(state)) MACHINES.computeIfAbsent(chunk, ignored -> new HashSet<>()).add(pos.immutable());
        else {
            var positions = MACHINES.get(chunk);
            if (positions != null && positions.remove(pos) && positions.isEmpty()) MACHINES.remove(chunk);
        }
    }

    private static void useLevel(ClientLevel world) {
        if (level != world) { MACHINES.clear(); level = world; }
    }

    private static boolean hasLuminousModel(BlockState state) {
        return state.getBlock() instanceof MachineBlock && state.getBlock() != ModContent.BLOCKS.get("super_structure");
    }

    private static MethodHandle spriteActivation() {
        // Sodium's public API keeps atlas animations active for geometry outside terrain batches.
        try {
            var api = Class.forName("net.caffeinemc.mods.sodium.api.texture.SpriteUtil");
            return MethodHandles.publicLookup().findVirtual(api, "markSpriteActive",
                    MethodType.methodType(void.class, TextureAtlasSprite.class)).bindTo(api.getField("INSTANCE").get(null));
        } catch (ClassNotFoundException absent) {
            return null;
        } catch (ReflectiveOperationException error) {
            LOGGER.warn("Sodium sprite activation API is unavailable; emissive animations may stop", error);
            return null;
        }
    }

    private static void animate(TextureAtlasSprite sprite) {
        if (activateSprite == null) return;
        try { activateSprite.invokeExact(sprite); }
        catch (Throwable error) {
            if (error instanceof Error fatal) throw fatal;
            activateSprite = null;
            LOGGER.warn("Could not activate an emissive atlas animation", error);
        }
    }

    private static final class EmissiveModel extends WrapperBlockStateModel {
        private final SpriteFinder sprites;
        private Object geometryKey;
        private BlockState geometryState;
        private Mesh glowing;
        private final Set<TextureAtlasSprite> animationSprites = new HashSet<>();
        private final RandomSource random = RandomSource.create();

        EmissiveModel(BlockStateModel model, SpriteFinder sprites) { super(model); this.sprites = sprites; }

        private boolean luminous(QuadView quad) {
            var texture = sprites.find(quad).contents().name();
            return texture.getNamespace().equals("emulator_i86_crafted")
                    && (texture.getPath().startsWith("block/light_") || texture.getPath().startsWith("block/display_")
                    || texture.getPath().equals("block/signal_cyan"));
        }

        @Override public void emitQuads(QuadEmitter emitter, BlockAndTintGetter world, BlockPos pos, BlockState state,
                RandomSource random, Predicate<Direction> cullTest) {
            emitter.pushTransform(quad -> !luminous(quad));
            try { wrapped.emitQuads(emitter, world, pos, state, random, cullTest); }
            finally { emitter.popTransform(); }
        }

        Mesh glowingMesh(BlockAndTintGetter world, BlockPos pos, BlockState state) {
            random.setSeed(state.getSeed(pos));
            var key = wrapped.createGeometryKey(world, pos, state, random);
            if (key != null && glowing != null && state == geometryState && key.equals(geometryKey)) {
                animationSprites.forEach(ClientEmissiveModels::animate);
                return glowing;
            }
            animationSprites.clear();
            var mesh = Renderer.get().mutableMesh();
            var emitter = mesh.emitter();
            emitter.pushTransform(quad -> {
                if (!luminous(quad)) return false;
                animationSprites.add(sprites.find(quad));
                for (int i = 0; i < 4; i++) quad.lightmap(i, 0xf000f0);
                return true;
            });
            random.setSeed(state.getSeed(pos));
            try { wrapped.emitQuads(emitter, world, pos, state, random, face -> false); }
            finally { emitter.popTransform(); }
            glowing = mesh.immutableCopy();
            geometryKey = key;
            geometryState = state;
            animationSprites.forEach(ClientEmissiveModels::animate);
            return glowing;
        }
    }
}
