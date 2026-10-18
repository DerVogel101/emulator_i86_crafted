package dervogel101.de.emulator_i86_crafted;

import dervogel101.de.emulator_i86_crafted.block.*;
import dervogel101.de.emulator_i86_crafted.server.*;
import dervogel101.de.emulator_i86_crafted.server.storage.*;
import dervogel101.de.i86.runtime.*;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.*;
import java.util.*;

public final class PunchcardGameTests {
    private static void text(PunchcardDevice device,String value) {
        byte[] bytes=Arrays.copyOf(value.getBytes(PunchcardDeck.CHARSET),32);
        for (int i=0;i<32;i++) device.write(i,bytes[i]);
        complete(device,PunchcardDevice.WRITE_NAME); ack(device);
    }
    @GameTest public void itemNamesAndDescriptionsSurviveReadingHoppersAndReload(GameTestHelper test) throws Exception {
        var writer=place(test,new BlockPos(2,3,2),true);
        writer.insert(new ItemStack(ModContent.ITEMS.get("punchcard_deck")));
        text(writer.device,"Initial name"); writer.device.write(0x2c,1); text(writer.device,"Deck description");
        complete(writer.device,PunchcardDevice.WRITE); ack(writer.device); complete(writer.device,PunchcardDevice.EJECT);
        var item=writer.pickup();
        test.assertValueEqual(item.getHoverName().getString(),"Initial name","Bus header initializes item name");
        test.assertValueEqual(item.get(ModComponents.PUNCHCARD_DESCRIPTION),"Deck description","Independent tooltip description");
        var firstName=net.minecraft.network.chat.Component.literal("Anvil A");
        var secondName=net.minecraft.network.chat.Component.literal("Anvil B");
        item.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,firstName);
        var reader=place(test,new BlockPos(4,3,2),false); reader.insert(item);
        var other=place(test,new BlockPos(6,3,2),false); var copy=item.copy();
        copy.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,secondName); other.insert(copy);
        complete(reader.device,PunchcardDevice.READ); ack(reader.device); complete(reader.device,PunchcardDevice.EJECT);
        var output=reader.removeItem(1,1);
        test.assertValueEqual(output.getHoverName().getString(),"Anvil A","Read and immediate hopper extraction retain anvil name");
        reader.setItem(1,output);
        ack(reader.device); reader.insert(copy); complete(reader.device,PunchcardDevice.EJECT);
        test.assertValueEqual(reader.pickup().getHoverName().getString(),"Anvil A","Blocked output keeps its own physical item name");
        reader.device.tick();
        test.assertValueEqual(reader.pickup().getHoverName().getString(),"Anvil B","Next output keeps its distinct name despite shared UUID");
        var level=test.getLevel(); var owner=ServerMachines.get(level.getServer());
        var tag=other.saveWithFullMetadata(level.registryAccess()); owner.ports.unload(other);
        level.removeBlockEntity(other.getBlockPos());
        var restored=(PunchcardBlockEntity)BlockEntity.loadStatic(other.getBlockPos(),other.getBlockState(),tag,level.registryAccess());
        level.setBlockEntity(restored); restored.hostTick(null);
        var recovered=restored.pickup();
        test.assertValueEqual(recovered.getHoverName().getString(),"Anvil B","Native reload and manual removal retain custom name");
        test.assertValueEqual(recovered.get(ModComponents.PUNCHCARD_NAME),"Initial name","Anvil name does not change media header");
        test.assertValueEqual(recovered.get(ModComponents.PUNCHCARD_DESCRIPTION),"Deck description","Description survives reader round trip");
        var renamedBlank=new ItemStack(ModContent.ITEMS.get("punchcard_deck"));
        renamedBlank.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,firstName);
        ack(writer.device); writer.insert(renamedBlank); text(writer.device,"Writer header");
        test.assertValueEqual(writer.pickup().getHoverName().getString(),"Anvil A","Writer header respects existing anvil name");
        test.succeed();
    }
    private static PunchcardBlockEntity place(GameTestHelper test,BlockPos pos,boolean writer) {
        test.setBlock(pos,ModContent.BLOCKS.get(writer ? "punchcard_writer" : "punchcard_reader"));
        return (PunchcardBlockEntity)test.getLevel().getBlockEntity(test.absolutePos(pos));
    }
    private static void complete(PunchcardDevice d,int command) { d.write(32,command); d.beforeInstruction(); d.tick(); }
    private static void ack(PunchcardDevice d) { d.write(32,PunchcardDevice.ACK); d.beforeInstruction(); }
    @GameTest public void hoppersRetainBlockedOutputAndDeduplicateDecks(GameTestHelper test) throws Exception {
        var writer=place(test,new BlockPos(2,3,2),true);
        var blanks=new ItemStack(ModContent.ITEMS.get("punchcard_deck"),4);
        var remaining=HopperBlockEntity.addItem(null,writer,blanks.split(1),Direction.UP);
        test.assertTrue(remaining.isEmpty(),"Hopper transfers its one-item packet");
        test.assertValueEqual(blanks.getCount(),3,"Hopper consumes exactly one blank deck");
        test.assertTrue(!writer.canTakeItemThroughFace(0,writer.getItem(0),Direction.DOWN),"Active deck cannot be extracted");
        writer.device.write(0,42); complete(writer.device,PunchcardDevice.WRITE); ack(writer.device);
        complete(writer.device,PunchcardDevice.EJECT); writer.persist();
        var first=writer.device.output().id();
        var held=writer.removeItem(1,1); writer.setItem(1,held);
        ack(writer.device);
        test.assertValueEqual(writer.device.output().id(),first,"Hopper rollback restores output");
        writer.device.insert(PunchcardDeck.blank()); writer.device.write(0,42); complete(writer.device,PunchcardDevice.WRITE); ack(writer.device);
        complete(writer.device,PunchcardDevice.EJECT);
        test.assertTrue(writer.device.active()!=null,"Occupied output blocks ejection");
        var original=writer.pickup(); writer.device.tick(); writer.persist();
        test.assertTrue(!original.isEmpty() && writer.device.output()!=null,"Unblocking completes pending eject");
        var second=writer.pickup();
        test.assertValueEqual(second.get(ModComponents.PUNCHCARD_ID),first,"Identical decks reuse their UUID");
        test.assertValueEqual(original.getMaxStackSize(),1,"Written deck is unstackable");
        var reader=place(test,new BlockPos(5,3,2),false);
        test.assertTrue(reader.insert(original),"Reader accepts sealed deck");
        test.assertTrue(!reader.canPlaceItem(0,blanks),"Reader rejects blanks before hopper transfer");
        var another=place(test,new BlockPos(7,3,2),false);
        test.assertTrue(another.insert(original.copy()),"Independent readers can share sealed contents");
        complete(reader.device,PunchcardDevice.READ); test.assertValueEqual(another.device.read(42),0,"Reader positions are independent");
        test.assertValueEqual(reader.device.read(0),42,"Data survived hopper and library round trip");
        test.succeed();
    }
    @GameTest public void mediaReferencesPersistenceAndVisualStates(GameTestHelper test) throws Exception {
        var writer=place(test,new BlockPos(2,3,2),true);
        test.assertTrue(writer.insert(new ItemStack(ModContent.ITEMS.get("punchcard_deck"))),"Hand insertion accepts blank");
        writer.hostTick(null);
        test.assertTrue(writer.getBlockState().getValue(PunchcardBlock.LOADED),"Input deck is visible");
        complete(writer.device,PunchcardDevice.WRITE); writer.hostTick(null);
        test.assertTrue(writer.getBlockState().getValue(MachineBlock.ACTIVE),"Completed transfer drives working-card model");
        ack(writer.device); complete(writer.device,PunchcardDevice.EJECT); writer.hostTick(null);
        test.assertTrue(writer.getBlockState().getValue(PunchcardBlock.OUTPUT),"Ejected deck is visible");
        var item=writer.pickup(); writer.hostTick(null);
        test.assertTrue(!writer.getBlockState().getValue(PunchcardBlock.OUTPUT),"Collecting clears output model");
        test.assertValueEqual(item.get(ModComponents.PUNCHCARD_COUNT),1,"Tooltip metadata counts whole cards");
        for (var facing : Direction.Plane.HORIZONTAL) {
            test.getLevel().setBlock(writer.getBlockPos(),writer.getBlockState().setValue(MachineBlock.FACING,facing),Block.UPDATE_ALL);
            test.assertValueEqual(writer.getBlockState().getValue(MachineBlock.FACING),facing,"Four horizontal facings");
        }
        test.succeed();
    }
    @GameTest public void pendingAndReadyStateSurviveNativeNbtRestore(GameTestHelper test) throws Exception {
        var writer=place(test,new BlockPos(2,3,2),true);
        writer.insert(new ItemStack(ModContent.ITEMS.get("punchcard_deck")));
        writer.device.write(0,77); writer.device.write(32,PunchcardDevice.WRITE); writer.device.beforeInstruction();
        var level=test.getLevel(); var owner=ServerMachines.get(level.getServer());
        var tag=writer.saveWithFullMetadata(level.registryAccess());
        owner.ports.unload(writer); level.removeBlockEntity(writer.getBlockPos());
        var restored=(PunchcardBlockEntity)BlockEntity.loadStatic(writer.getBlockPos(),writer.getBlockState(),tag,level.registryAccess());
        level.setBlockEntity(restored); restored.hostTick(null);
        test.assertValueEqual(restored.device.read(32),PunchcardDevice.WRITE,"Pending write survived native NBT");
        restored.device.tick(); restored.persist();
        test.assertValueEqual(restored.device.active().row(0,0)[0],(byte)77,"Captured write survived native NBT");
        var secondTag=restored.saveWithFullMetadata(level.registryAccess());
        owner.ports.unload(restored); level.removeBlockEntity(restored.getBlockPos());
        var ready=(PunchcardBlockEntity)BlockEntity.loadStatic(restored.getBlockPos(),restored.getBlockState(),secondTag,level.registryAccess());
        level.setBlockEntity(ready); ready.hostTick(null);
        test.assertTrue((ready.device.read(33)&PunchcardDevice.READY)!=0,"Unread completion survived native NBT");
        test.assertValueEqual(ready.device.read(42),1,"Row cursor survived native NBT"); test.succeed();
    }
    @GameTest public void breakingDropsBothDecks(GameTestHelper test) throws Exception {
        var pos=new BlockPos(2,3,2); var writer=place(test,pos,true);
        writer.insert(new ItemStack(ModContent.ITEMS.get("punchcard_deck")));
        complete(writer.device,PunchcardDevice.WRITE); ack(writer.device); complete(writer.device,PunchcardDevice.EJECT); ack(writer.device);
        writer.insert(new ItemStack(ModContent.ITEMS.get("punchcard_deck"))); complete(writer.device,PunchcardDevice.WRITE);
        test.getLevel().destroyBlock(test.absolutePos(pos),true);
        var area=new net.minecraft.world.phys.AABB(test.absolutePos(pos)).inflate(2);
        var drops=test.getLevel().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,area).stream()
                .filter(entity -> entity.getItem().getItem() instanceof PunchcardDeckItem).mapToInt(entity -> entity.getItem().getCount()).sum();
        test.assertValueEqual(drops,2,"Breaking preserves active and output decks"); test.succeed();
    }
}
