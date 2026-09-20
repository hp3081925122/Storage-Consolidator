package org.hp.storage_consolidator.compat;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.EntityBlock;
import net.p3pp3rf1y.sophisticatedcore.api.IStorageWrapper;
import net.p3pp3rf1y.sophisticatedcore.inventory.InventoryHandler;
import net.p3pp3rf1y.sophisticatedcore.settings.memory.MemorySettingsCategory;
import net.p3pp3rf1y.sophisticatedcore.settings.nosort.NoSortSettingsCategory;
import net.p3pp3rf1y.sophisticatedcore.upgrades.IOverflowResponseUpgrade;
import org.hp.storage_consolidator.Storage_consolidator;
import org.hp.storage_consolidator.access.SophisticatedInventoryAccess;

/** 在独立测试世界验证真实箱子库存，不修改玩家存档。 */
@net.minecraftforge.gametest.GameTestHolder("storage_consolidator")
@net.minecraftforge.gametest.PrefixGameTestTemplate(false)
public final class SophisticatedCompatSelfTest {
    private SophisticatedCompatSelfTest() {}

    /** 在服务端配置加载后的测试世界中运行，异常会使回归失败。 */
    @net.minecraft.gametest.framework.GameTest(template = "compat_empty")
    public static void run(net.minecraft.gametest.framework.GameTestHelper helper) {
        try {
            // 通过真实注册方块构造独立箱子实体，避免模拟处理器掩盖上游行为。
            var block = BuiltInRegistries.BLOCK.get(new ResourceLocation("sophisticatedstorage", "diamond_chest"));
            var entity = ((EntityBlock) block).newBlockEntity(BlockPos.ZERO, block.defaultBlockState());
            // 使用测试世界的注册表和配方上下文，但不将箱子放入世界。
            entity.setLevel(helper.getLevel());
            IStorageWrapper wrapper = (IStorageWrapper) entity.getClass().getMethod("getStorageWrapper").invoke(entity);
            InventoryHandler inventory = wrapper.getInventoryHandler();
            // 从真实方块取得与 Tom 访问相同的外部能力处理器。
            var cached = (net.minecraftforge.items.IItemHandler) entity.getClass()
                    .getMethod("getExternalItemHandler", net.minecraft.core.Direction.class)
                    .invoke(entity, net.minecraft.core.Direction.DOWN);
            require(inventory instanceof SophisticatedInventoryAccess, "inventory mixin");
            var access = (SophisticatedInventoryAccess) inventory;

            // 两个槽位都可接收铁锭，只允许指定槽位增加；模拟不能改库存。
            inventory.setStackInSlot(0, new ItemStack(Items.IRON_INGOT, 55));
            inventory.setStackInSlot(1, new ItemStack(Items.IRON_INGOT, 56));
            try (var scope = new ConsolidationScope()) {
                require(cached.insertItem(1, new ItemStack(Items.IRON_INGOT, 8), true).isEmpty(), "simulate accepted");
                require(inventory.getStackInSlot(1).getCount() == 56, "simulate unchanged");
                require(cached.insertItem(1, new ItemStack(Items.IRON_INGOT, 8), false).isEmpty(), "exact slot accepted");
            }
            require(inventory.getStackInSlot(0).getCount() == 55 && inventory.getStackInSlot(1).getCount() == 64, "exact slot conservation");

            // 安装真实销毁升级，满槽整理必须返回全部剩余物。
            var voidItem = BuiltInRegistries.ITEM.get(new ResourceLocation("sophisticatedstorage", "void_upgrade"));
            require(voidItem != Items.AIR, "void upgrade registered");
            wrapper.getUpgradeHandler().setStackInSlot(0, new ItemStack(voidItem));
            require(!wrapper.getUpgradeHandler().getWrappersThatImplementFromMainStorage(IOverflowResponseUpgrade.class).isEmpty(), "void upgrade active");
            try (var scope = new ConsolidationScope()) {
                require(cached.insertItem(1, new ItemStack(Items.IRON_INGOT, 8), true).getCount() == 8, "void simulation remainder");
                require(cached.insertItem(1, new ItemStack(Items.IRON_INGOT, 8), false).getCount() == 8, "void real remainder");
                require(inventory.getStackInSlot(1).getCount() == 64, "void unchanged");
            }
            wrapper.getUpgradeHandler().setStackInSlot(0, ItemStack.EMPTY);

            // 尊重用户指定的禁止整理槽位，来源与目标均不可操作。
            var noSort = wrapper.getSettingsHandler().getTypeCategory(NoSortSettingsCategory.class);
            noSort.selectSlot(0);
            try (var scope = new ConsolidationScope()) {
                require(cached.extractItem(0, 1, false).isEmpty(), "no-sort extract");
                require(cached.insertItem(0, new ItemStack(Items.IRON_INGOT), false).getCount() == 1, "no-sort insert");
            }
            noSort.unselectSlot(0);

            // 空槽被上游锁定时不写入，作用域结束后恢复正常操作。
            inventory.setShouldInsertIntoEmpty(() -> false);
            try (var scope = new ConsolidationScope()) {
                require(cached.insertItem(3, new ItemStack(Items.IRON_INGOT), false).getCount() == 1, "locked empty slot");
            }
            inventory.setShouldInsertIntoEmpty(() -> true);

            // 保留记忆过滤，不向记忆为泥土的空槽塞入铁锭。
            inventory.setStackInSlot(2, new ItemStack(Items.DIRT));
            var memory = wrapper.getSettingsHandler().getTypeCategory(MemorySettingsCategory.class);
            memory.selectSlot(2);
            inventory.setStackInSlot(2, ItemStack.EMPTY);
            try (var scope = new ConsolidationScope()) {
                require(cached.insertItem(2, new ItemStack(Items.IRON_INGOT), false).getCount() == 1, "memory filter");
            }

            // 真实堆叠升级允许超过六十四，适配不能截断上游容量。
            var stackItem = BuiltInRegistries.ITEM.get(new ResourceLocation("sophisticatedstorage", "stack_upgrade_tier_1"));
            require(stackItem != Items.AIR, "stack upgrade registered");
            wrapper.getUpgradeHandler().setStackInSlot(0, new ItemStack(stackItem));
            require(access.storageConsolidator$stackLimit(1, new ItemStack(Items.IRON_INGOT)) > 64, "stack capacity");
            try (var scope = new ConsolidationScope()) {
                require(cached.insertItem(1, new ItemStack(Items.IRON_INGOT, 8), false).isEmpty(), "stack upgraded insert");
                require(inventory.getStackInSlot(1).getCount() == 72, "stack upgraded count");
            }
            require(!ConsolidationScope.active(), "scope released");

            // 安装真实输入过滤升级，确认定向插入没有绕过外层白名单。
            wrapper.getUpgradeHandler().setStackInSlot(0, ItemStack.EMPTY);
            var filterItem = BuiltInRegistries.ITEM.get(new ResourceLocation("sophisticatedstorage", "filter_upgrade"));
            require(filterItem != Items.AIR, "filter upgrade registered");
            wrapper.getUpgradeHandler().setStackInSlot(0, new ItemStack(filterItem));
            var filters = wrapper.getUpgradeHandler().getWrappersThatImplement(net.p3pp3rf1y.sophisticatedcore.api.IIOFilterUpgrade.class);
            require(!filters.isEmpty(), "input filter upgrade active");
            var filter = filters.get(0).getInputFilter().orElseThrow();
            filter.setAllowList(true);
            filter.getFilterHandler().setStackInSlot(0, new ItemStack(Items.DIRT));
            wrapper.refreshInventoryForInputOutput();
            try (var scope = new ConsolidationScope()) {
                require(cached.insertItem(3, new ItemStack(Items.IRON_INGOT), false).getCount() == 1, "external input filter denied");
                require(cached.insertItem(3, new ItemStack(Items.DIRT), false).isEmpty(), "external input filter allowed");
                require(inventory.getStackInSlot(3).is(Items.DIRT), "external filter destination");
            }

            // 自动合成升级不能把整理中的铁锭转换成铁块。
            var compactItem = BuiltInRegistries.ITEM.get(new ResourceLocation("sophisticatedstorage", "compacting_upgrade"));
            require(compactItem != Items.AIR, "compacting upgrade registered");
            wrapper.getUpgradeHandler().setStackInSlot(0, new ItemStack(compactItem));
            try (var scope = new ConsolidationScope()) {
                require(cached.insertItem(4, new ItemStack(Items.IRON_INGOT, 9), false).isEmpty(), "compacting insert");
                require(inventory.getStackInSlot(4).is(Items.IRON_INGOT)
                        && inventory.getStackInSlot(4).getCount() == 9, "compacting preserves items");
            }
            // 非整理操作不受禁止整理规则拦截，证明作用域没有泄漏。
            wrapper.getUpgradeHandler().setStackInSlot(0, ItemStack.EMPTY);
            noSort.selectSlot(4);
            require(cached.extractItem(4, 1, false).getCount() == 1, "ordinary extraction restored");
            Storage_consolidator.LOGGER.info("Sophisticated compatibility self-test PASSED: external-handler, exact-slot, simulation, void, no-sort, locked-empty, memory-filter, stack-upgrade, io-filter, compacting, ordinary-operation, scope-release");
            helper.succeed();
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Sophisticated compatibility self-test setup failed", exception);
        }
    }

    /** 带场景名称报告失败，便于从启动日志准确定位。 */
    private static void require(boolean condition, String scenario) {
        if (!condition) throw new IllegalStateException("Sophisticated compatibility self-test FAILED: " + scenario);
    }
}
