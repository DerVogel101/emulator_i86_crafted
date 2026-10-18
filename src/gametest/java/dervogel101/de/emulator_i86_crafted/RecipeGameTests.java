package dervogel101.de.emulator_i86_crafted;

import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.*;

import java.util.*;

public final class RecipeGameTests {
    private static CraftingInput input(ShapedRecipe recipe) {
        return CraftingInput.of(recipe.getWidth(), recipe.getHeight(), recipe.getIngredients().stream()
                .map(ingredient -> ingredient.map(value -> new ItemStack(value.items().findFirst().orElseThrow().value()))
                        .orElse(ItemStack.EMPTY)).toList());
    }

    @GameTest public void everyItemIsCraftableAndWirelessMachinesNeedAntennas(GameTestHelper test) {
        var level = test.getLevel();
        var crafted = new HashSet<Item>();
        var wireless = Set.of("ender_tunnel_array", "gpu_array", "gpio_controller", "redstone_port", "sign_port",
                "keyboard_controller", "keyboard", "punchcard_controller", "punchcard_reader", "punchcard_writer");
        var counts = Map.of("super_structure", 2, "memory_chip_material", 2, "pcb_material", 2, "punchcard_deck", 4);
        int recipes = 0;
        for (var holder : level.getServer().getRecipeManager().getRecipes()) {
            var id = holder.id().identifier();
            if (!id.getNamespace().equals(Emulator_i86_crafted.MOD_ID)) continue;
            recipes++;
            ItemStack result;
            if (holder.value() instanceof ShapedRecipe recipe) {
                var input = input(recipe);
                test.assertTrue(recipe.matches(input, level), id + ": ingredients match");
                result = recipe.assemble(input);
                var invalid = new ArrayList<>(input.items());
                for (int i = 0; i < invalid.size(); i++) if (!invalid.get(i).isEmpty()) {
                    invalid.set(i, new ItemStack(Items.BEDROCK));
                    break;
                }
                test.assertTrue(!recipe.matches(CraftingInput.of(input.width(), input.height(), invalid), level),
                        id + ": wrong ingredients rejected");
                if (ModContent.BLOCKS.containsKey(id.getPath()) && !id.getPath().equals("super_structure"))
                    test.assertTrue(input.items().stream().anyMatch(stack -> stack.is(ModContent.ITEMS.get("super_structure"))),
                            id + ": machines use the base structure");
                if (wireless.contains(id.getPath()))
                    test.assertTrue(input.items().stream().anyMatch(stack -> stack.is(ModContent.ITEMS.get("antenna_array_material"))),
                            id + ": wireless machines use an antenna");
            } else {
                test.assertTrue(holder.value() instanceof BlastingRecipe, id + ": wafer uses the blast furnace");
                var recipe = (BlastingRecipe) holder.value();
                var quartz = new SingleRecipeInput(new ItemStack(Items.SMOOTH_QUARTZ));
                test.assertTrue(recipe.matches(quartz, level), "Smooth quartz can be blasted into silicon");
                test.assertValueEqual(recipe.cookingTime(), 400, "Wafer blasting time");
                result = recipe.assemble(quartz);
            }
            var output = id.getPath().equals("speed_shard_3_fake") ? "speed_shard_3"
                    : id.getPath().replace("_from_blasting", "");
            test.assertTrue(result.is(ModContent.ITEMS.get(output)), id + ": expected output item");
            test.assertValueEqual(result.getCount(), counts.getOrDefault(output, 1), id + ": output count");
            crafted.add(result.getItem());
            var unlock = level.getServer().getAdvancements().get(ModContent.id("recipes/" + id.getPath()));
            test.assertTrue(unlock != null && unlock.value().rewards().recipes().contains(holder.id()),
                    id + ": survival recipe unlock loaded");
        }
        test.assertValueEqual(recipes, ModContent.ITEMS.size() + 1, "One recipe per item plus the alternate shard");
        test.assertValueEqual(crafted, new HashSet<>(ModContent.ITEMS.values()), "Every registered item is obtainable");
        test.succeed();
    }

    @GameTest public void alternateShardHasTheSameIdentityAndPersistsOnlyCrude(GameTestHelper test) {
        var recipes = new HashMap<String, ShapedRecipe>();
        for (var holder : test.getLevel().getServer().getRecipeManager().getRecipes())
            if (holder.id().identifier().getNamespace().equals(Emulator_i86_crafted.MOD_ID)
                    && holder.value() instanceof ShapedRecipe recipe) recipes.put(holder.id().identifier().getPath(), recipe);
        var regular = recipes.get("speed_shard_3");
        var alternate = recipes.get("speed_shard_3_fake");
        var real = regular.assemble(input(regular));
        var crude = alternate.assemble(input(alternate));
        test.assertTrue(!regular.group().isEmpty() && regular.group().equals(alternate.group()), "Alternates share a recipe group");
        test.assertTrue(input(alternate).items().stream().anyMatch(stack -> stack.is(ModContent.ITEMS.get("speed_shard_2"))),
                "Crude tier III upgrades tier II");
        test.assertTrue(!real.has(ModComponents.CRUDE) && Boolean.TRUE.equals(crude.get(ModComponents.CRUDE)), "Only alternate has crude=true");
        test.assertValueEqual(crude.getHoverName(), real.getHoverName(), "Same displayed name");
        test.assertValueEqual(ModContent.SPEED_SHARDS.get(crude.getItem()), ModContent.SPEED_SHARDS.get(real.getItem()), "Same speed tier");
        var withoutCrude = crude.copy(); withoutCrude.remove(ModComponents.CRUDE);
        test.assertTrue(ItemStack.matches(real, withoutCrude), "Only the crude component differs");
        var ops = test.getLevel().registryAccess().createSerializationContext(NbtOps.INSTANCE);
        var saved = ItemStack.CODEC.encodeStart(ops, crude).getOrThrow();
        test.assertTrue(ItemStack.matches(crude, ItemStack.CODEC.parse(ops, saved).getOrThrow()), "Crude survives save/reload");
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), test.getLevel().registryAccess());
        try {
            ItemStack.STREAM_CODEC.encode(buffer, crude);
            test.assertTrue(ItemStack.matches(crude, ItemStack.STREAM_CODEC.decode(buffer)), "Crude survives network synchronization");
        } finally { buffer.release(); }
        test.succeed();
    }
}
