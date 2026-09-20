package org.hp.storage_consolidator.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.tom.storagemod.block.InventoryCableBlock;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.IItemHandlerModifiable;
import org.hp.storage_consolidator.Storage_consolidator;

import java.util.ArrayList;
import java.util.List;

/**
 * 生成包含多种容器的整理压力测试场景。
 */
public final class StressTestCommand {
    private static final int DEFAULT_SLOTS_PER_CONTAINER = 128;
    private static final int DEFAULT_COPIES_PER_TYPE = 32;
    private static final int MAX_COPIES_PER_TYPE = 64;
    private static final int GRID_WIDTH = 16;

    private static final List<ResourceLocation> TEST_ITEMS = List.of(
            new ResourceLocation("minecraft", "dirt"),
            new ResourceLocation("minecraft", "cobblestone"),
            new ResourceLocation("minecraft", "stone"),
            new ResourceLocation("minecraft", "oak_log"),
            new ResourceLocation("minecraft", "iron_ingot"),
            new ResourceLocation("minecraft", "gold_ingot"),
            new ResourceLocation("minecraft", "redstone"),
            new ResourceLocation("minecraft", "lapis_lazuli"),
            new ResourceLocation("minecraft", "coal"),
            new ResourceLocation("minecraft", "quartz"),
            new ResourceLocation("minecraft", "amethyst_shard"),
            new ResourceLocation("minecraft", "paper"),
            new ResourceLocation("minecraft", "glass"),
            new ResourceLocation("minecraft", "sand"),
            new ResourceLocation("minecraft", "gravel"),
            new ResourceLocation("minecraft", "netherrack")
    );

    private static final List<ContainerDefinition> CONTAINER_DEFINITIONS = List.of(
            new ContainerDefinition("Vanilla Chest", new ResourceLocation("minecraft", "chest")),
            new ContainerDefinition("Vanilla Barrel", new ResourceLocation("minecraft", "barrel")),
            new ContainerDefinition("Vanilla Shulker Box", new ResourceLocation("minecraft", "shulker_box")),
            new ContainerDefinition("Vanilla Hopper", new ResourceLocation("minecraft", "hopper")),
            new ContainerDefinition("Sophisticated Chest", new ResourceLocation("sophisticatedstorage", "chest")),
            new ContainerDefinition("Sophisticated Barrel", new ResourceLocation("sophisticatedstorage", "barrel")),
            new ContainerDefinition("Sophisticated Diamond Chest", new ResourceLocation("sophisticatedstorage", "diamond_chest")),
            new ContainerDefinition("Lootr Chest", new ResourceLocation("lootr", "lootr_chest")),
            new ContainerDefinition("Tom's Filing Cabinet", new ResourceLocation("toms_storage", "filing_cabinet"))
    );

    private StressTestCommand() {
    }

    /**
     * 注册管理员使用的压力测试命令，参数表示每个容器的槽位数和每类容器的复制数量。
     */
    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("storage_consolidator")
                        .then(Commands.literal("stress")
                                .requires(source -> source.hasPermission(2))
                                .executes(context -> execute(
                                        context,
                                        DEFAULT_SLOTS_PER_CONTAINER,
                                        DEFAULT_COPIES_PER_TYPE
                                ))
                                .then(Commands.argument("slotsPerContainer", IntegerArgumentType.integer(1, 2048))
                                        .executes(context -> execute(
                                                context,
                                                IntegerArgumentType.getInteger(context, "slotsPerContainer"),
                                                DEFAULT_COPIES_PER_TYPE
                                        ))
                                        .then(Commands.argument(
                                                        "copiesPerType",
                                                        IntegerArgumentType.integer(1, MAX_COPIES_PER_TYPE)
                                                )
                                                .executes(context -> execute(
                                                        context,
                                                        IntegerArgumentType.getInteger(context, "slotsPerContainer"),
                                                        IntegerArgumentType.getInteger(context, "copiesPerType")
                                                )))))
        );
    }

    /**
     * 在玩家前方放置 Tom's Storage 网络、不同模组容器，并写入大量可堆叠物品。
     */
    private static int execute(
            CommandContext<CommandSourceStack> context,
            int slotsPerContainer,
            int copiesPerType
    )
            throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayerOrException();
        ServerLevel level = player.serverLevel();
        Block terminalBlock = getBlock(new ResourceLocation("toms_storage", "storage_terminal"));
        Block cableBlock = getBlock(new ResourceLocation("toms_storage", "inventory_cable"));
        Block connectorBlock = getBlock(new ResourceLocation("toms_storage", "inventory_connector"));
        if (terminalBlock == null || cableBlock == null || connectorBlock == null) {
            source.sendFailure(Component.translatable("message.storage_consolidator.stress.no_terminal"));
            return 0;
        }

        List<ContainerDefinition> availableContainers = CONTAINER_DEFINITIONS.stream()
                .filter(definition -> getBlock(definition.blockId()) != null)
                .toList();
        if (availableContainers.isEmpty()) {
            source.sendFailure(Component.translatable("message.storage_consolidator.stress.no_containers"));
            return 0;
        }

        Direction forward = player.getDirection();
        Direction side = forward.getClockWise();
        BlockPos terminalPos = player.blockPosition().relative(forward, 5);
        int totalContainers = availableContainers.size() * copiesPerType;
        List<PlacedContainer> placedContainers = new ArrayList<>(totalContainers);
        List<BlockPos> cablePositions = new ArrayList<>(totalContainers);

        // 先铺设测试区域的支撑方块，避免生成的容器悬空。
        placeBlock(level, terminalPos.below(), Blocks.STONE.defaultBlockState());
        placeBlock(level, terminalPos, terminalBlock.defaultBlockState());
        for (int index = 0; index < totalContainers; index++) {
            int row = index / GRID_WIDTH;
            int column = index % GRID_WIDTH;
            ContainerDefinition definition = availableContainers.get(index % availableContainers.size());
            BlockPos cablePos = terminalPos.relative(side, column + 1).relative(forward, row);
            BlockPos connectorPos = cablePos.above();
            BlockPos containerPos = connectorPos.above();
            placeBlock(level, cablePos.below(), Blocks.STONE.defaultBlockState());
            placeBlock(level, cablePos, cableBlock.defaultBlockState());
            placeBlock(level, connectorPos, connectorBlock.defaultBlockState());
            placeBlock(level, containerPos, getBlock(definition.blockId()).defaultBlockState());
            cablePositions.add(cablePos);
            placedContainers.add(new PlacedContainer(containerPos, definition));
        }

        // 所有方块落位后再一次性计算线缆连接，减少逐格更新引发的能力缓存失效。
        for (BlockPos cablePos : cablePositions) {
            BlockState state = level.getBlockState(cablePos);
            if (state.getBlock() instanceof InventoryCableBlock cable) {
                level.setBlock(cablePos, cable.withConnectionProperties(level, cablePos), Block.UPDATE_CLIENTS);
            }
        }

        int actualStacks = 0;
        for (int index = 0; index < placedContainers.size(); index++) {
            actualStacks += fillContainer(level, placedContainers.get(index).position(), slotsPerContainer, index);
        }
        int generatedStacks = actualStacks;
        source.sendSuccess(
                () -> Component.translatable(
                        "message.storage_consolidator.stress.created",
                        generatedStacks,
                        placedContainers.size(),
                        copiesPerType,
                        terminalPos.getX() + " " + terminalPos.getY() + " " + terminalPos.getZ()
                ),
                true
        );
        Storage_consolidator.LOGGER.debug(
                "Created storage consolidation stress scene for {}: containers={}, stacks={}",
                player.getGameProfile().getName(), placedContainers.size(), generatedStacks
        );
        return generatedStacks;
    }

    /**
     * 从当前注册表解析方块，模组未安装时返回空值而不是硬依赖该模组。
     */
    private static Block getBlock(ResourceLocation id) {
        return BuiltInRegistries.BLOCK.getOptional(id).orElse(null);
    }

    /**
     * 放置一个测试方块并刷新其邻居状态。
     */
    private static void placeBlock(ServerLevel level, BlockPos position, BlockState state) {
        level.setBlock(position, state, Block.UPDATE_ALL);
    }

    /**
     * 通过 NeoForge 物品能力向容器的每个槽位写入不同数量的可堆叠物品。
     */
    private static int fillContainer(ServerLevel level, BlockPos position, int slotsPerContainer, int containerIndex) {
        BlockState state = level.getBlockState(position);
        BlockEntity blockEntity = level.getBlockEntity(position);
        IItemHandler handler = blockEntity == null
                ? null
                : blockEntity.getCapability(ForgeCapabilities.ITEM_HANDLER, null).orElse(null);
        if (handler == null) {
            return 0;
        }

        int slots = Math.min(slotsPerContainer, handler.getSlots());
        int written = 0;
        for (int slot = 0; slot < slots; slot++) {
            Item item = BuiltInRegistries.ITEM.get(TEST_ITEMS.get((slot + containerIndex * 3) % TEST_ITEMS.size()));
            int requested = 8 + ((slot * 13 + containerIndex * 7) % 49);
            int limit = Math.min(handler.getSlotLimit(slot), new ItemStack(item).getMaxStackSize());
            int amount = Math.min(requested, limit);
            if (amount <= 0) {
                continue;
            }

            ItemStack stack = new ItemStack(item, amount);
            if (handler instanceof IItemHandlerModifiable modifiable) {
                modifiable.setStackInSlot(slot, stack);
                written++;
                continue;
            }

            ItemStack remainder = handler.insertItem(slot, stack, false);
            if (remainder.getCount() < stack.getCount()) {
                written++;
            }
        }
        return written;
    }

    private record ContainerDefinition(String name, ResourceLocation blockId) {
    }

    private record PlacedContainer(BlockPos position, ContainerDefinition definition) {
    }
}
