package org.hp.storage_consolidator.compat;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInstance;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import com.mojang.serialization.MapCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.EntityBlock;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.server.loading.ServerModLoader;
import net.p3pp3rf1y.sophisticatedcore.api.IStorageWrapper;
import net.p3pp3rf1y.sophisticatedcore.inventory.InventoryHandler;
import net.p3pp3rf1y.sophisticatedcore.settings.memory.MemorySettingsCategory;
import net.p3pp3rf1y.sophisticatedcore.settings.nosort.NoSortSettingsCategory;
import net.p3pp3rf1y.sophisticatedcore.upgrades.IOverflowResponseUpgrade;
import org.hp.storage_consolidator.Storage_consolidator;
import org.hp.storage_consolidator.access.FilteredInventoryAccess;
import org.hp.storage_consolidator.access.SophisticatedInventoryAccess;


/** 在独立测试世界验证真实箱子库存，不修改玩家存档。 */
public final class SophisticatedCompatSelfTest {
    private static final Identifier TEST_ID = Identifier.fromNamespaceAndPath(
            Storage_consolidator.MODID, "sophisticated_compat");
    private SophisticatedCompatSelfTest() {}

    /** 注册空环境和真实兼容回归测试实例。 */
    public static void registerGameTest(RegisterGameTestsEvent event) {
        // 自定义测试实例只在专用 GameTestServer 注册，避免普通客户端同步未注册的测试类型。
        if (!ServerModLoader.isGameTestServer()) return;
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(
                Identifier.fromNamespaceAndPath(Storage_consolidator.MODID, "compat"),
                new TestEnvironmentDefinition.AllOf());
        TestData<Holder<TestEnvironmentDefinition<?>>> data = new TestData<>(
                environment,
                Identifier.fromNamespaceAndPath(Storage_consolidator.MODID, "compat_empty"),
                100,
                0,
                false);
        GameTestInstance test = new DirectGameTestInstance(data);
        event.registerTest(TEST_ID, test);
    }

    /** 直接持有测试回调，避免 26.1.2 在注册表初始化后查找旧式测试函数。 */
    private static final class DirectGameTestInstance extends GameTestInstance {
        private DirectGameTestInstance(TestData<Holder<TestEnvironmentDefinition<?>>> data) {
            super(data);
        }

        @Override
        public void run(GameTestHelper helper) {
            SophisticatedCompatSelfTest.run(helper);
        }

        @Override
        public MapCodec<? extends GameTestInstance> codec() {
            return MapCodec.unit(this);
        }

        @Override
        protected net.minecraft.network.chat.MutableComponent typeDescription() {
            return net.minecraft.network.chat.Component.translatable("test_instance.type.function");
        }
    }

    /** 在服务端配置加载后的测试世界中运行，异常会使回归失败。 */
    public static void run(GameTestHelper helper) {
        try {
            // 通过真实注册方块构造独立箱子实体，避免模拟处理器掩盖上游行为。
            var block = BuiltInRegistries.BLOCK.getValue(Identifier.fromNamespaceAndPath("sophisticatedstorage", "diamond_chest"));
            var entity = ((EntityBlock) block).newBlockEntity(BlockPos.ZERO, block.defaultBlockState());
            // 使用测试世界的注册表和配方上下文，但不将箱子放入世界。
            entity.setLevel(helper.getLevel());
            IStorageWrapper wrapper = (IStorageWrapper) entity.getClass().getMethod("getStorageWrapper").invoke(entity);
            InventoryHandler inventory = wrapper.getInventoryHandler();
            // 从真实方块取得与 Tom 访问相同的外部能力处理器。
            var cached = getExternalHandler(entity);
            Storage_consolidator.LOGGER.info(
                    "Sophisticated compatibility external handler class: {}",
                    cached.getClass().getName());
            require(inventory instanceof SophisticatedInventoryAccess, "inventory mixin");
            var access = (SophisticatedInventoryAccess) inventory;

            // 两个槽位都可接收铁锭，只允许指定槽位增加；模拟不能改库存。
            inventory.setStackInSlot(0, new ItemStack(Items.IRON_INGOT, 55));
            inventory.setStackInSlot(1, new ItemStack(Items.IRON_INGOT, 56));
            try (var scope = new ConsolidationScope()) {
                require(insertRemainder(cached, 1, new ItemStack(Items.IRON_INGOT, 8), false).isEmpty(), "simulate accepted");
                require(inventory.getStackInSlot(1).getCount() == 56, "simulate unchanged");
                require(insertRemainder(cached, 1, new ItemStack(Items.IRON_INGOT, 8), true).isEmpty(), "exact slot accepted");
            }
            require(inventory.getStackInSlot(0).getCount() == 55 && inventory.getStackInSlot(1).getCount() == 64, "exact slot conservation");

            // 安装真实销毁升级，满槽整理必须返回全部剩余物。
            var voidItem = BuiltInRegistries.ITEM.getValue(Identifier.fromNamespaceAndPath("sophisticatedstorage", "void_upgrade"));
            require(voidItem != Items.AIR, "void upgrade registered");
            wrapper.getUpgradeHandler().setStackInSlot(0, new ItemStack(voidItem));
            require(!wrapper.getUpgradeHandler().getWrappersThatImplementFromMainStorage(IOverflowResponseUpgrade.class).isEmpty(), "void upgrade active");
            try (var scope = new ConsolidationScope()) {
                require(insertRemainder(cached, 1, new ItemStack(Items.IRON_INGOT, 8), false).getCount() == 8, "void simulation remainder");
                require(insertRemainder(cached, 1, new ItemStack(Items.IRON_INGOT, 8), true).getCount() == 8, "void real remainder");
                require(inventory.getStackInSlot(1).getCount() == 64, "void unchanged");
            }
            wrapper.getUpgradeHandler().setStackInSlot(0, ItemStack.EMPTY);

            // 尊重用户指定的禁止整理槽位，来源与目标均不可操作。
            var noSort = wrapper.getSettingsHandler().getTypeCategory(NoSortSettingsCategory.class);
            noSort.selectSlot(0);
            try (var scope = new ConsolidationScope()) {
                require(extractStack(cached, 0, 1, false).isEmpty(), "no-sort extract");
                require(insertRemainder(cached, 0, new ItemStack(Items.IRON_INGOT), true).getCount() == 1, "no-sort insert");
            }
            noSort.unselectSlot(0);

            // 空槽被上游锁定时不写入，作用域结束后恢复正常操作。
            inventory.setShouldInsertIntoEmpty(() -> false);
            try (var scope = new ConsolidationScope()) {
                require(insertRemainder(cached, 3, new ItemStack(Items.IRON_INGOT), true).getCount() == 1, "locked empty slot");
            }
            inventory.setShouldInsertIntoEmpty(() -> true);

            // 保留记忆过滤，不向记忆为泥土的空槽塞入铁锭。
            inventory.setStackInSlot(2, new ItemStack(Items.DIRT));
            var memory = wrapper.getSettingsHandler().getTypeCategory(MemorySettingsCategory.class);
            memory.selectSlot(2);
            inventory.setStackInSlot(2, ItemStack.EMPTY);
            try (var scope = new ConsolidationScope()) {
                require(insertRemainder(cached, 2, new ItemStack(Items.IRON_INGOT), true).getCount() == 1, "memory filter");
            }

            // 真实堆叠升级允许超过六十四，适配不能截断上游容量。
            var stackItem = BuiltInRegistries.ITEM.getValue(Identifier.fromNamespaceAndPath("sophisticatedstorage", "stack_upgrade_tier_1"));
            require(stackItem != Items.AIR, "stack upgrade registered");
            wrapper.getUpgradeHandler().setStackInSlot(0, new ItemStack(stackItem));
            require(access.storageConsolidator$stackLimit(1, new ItemStack(Items.IRON_INGOT)) > 64, "stack capacity");
            try (var scope = new ConsolidationScope()) {
                require(insertRemainder(cached, 1, new ItemStack(Items.IRON_INGOT, 8), true).isEmpty(), "stack upgraded insert");
                require(inventory.getStackInSlot(1).getCount() == 72, "stack upgraded count");
            }
            require(!ConsolidationScope.active(), "scope released");

            // 安装真实输入过滤升级，确认定向插入没有绕过外层白名单。
            wrapper.getUpgradeHandler().setStackInSlot(0, ItemStack.EMPTY);
            var filterItem = BuiltInRegistries.ITEM.getValue(Identifier.fromNamespaceAndPath("sophisticatedstorage", "filter_upgrade"));
            require(filterItem != Items.AIR, "filter upgrade registered");
            wrapper.getUpgradeHandler().setStackInSlot(0, new ItemStack(filterItem));
            var filters = wrapper.getUpgradeHandler().getWrappersThatImplement(net.p3pp3rf1y.sophisticatedcore.api.IIOFilterUpgrade.class);
            require(!filters.isEmpty(), "input filter upgrade active");
            var filter = filters.getFirst().getInputFilter().orElseThrow();
            filter.setAllowList(true);
            filter.getFilterHandler().setStackInSlot(0, new ItemStack(Items.DIRT));
            wrapper.refreshInventoryForInputOutput();
            var filtered = getExternalHandler(entity);
            require(filtered instanceof FilteredInventoryAccess, "filtered wrapper mixin");
            try (var scope = new ConsolidationScope()) {
                require(insertRemainder(filtered, 3, new ItemStack(Items.IRON_INGOT), true).getCount() == 1, "external input filter denied");
                require(insertRemainder(filtered, 3, new ItemStack(Items.DIRT), true).isEmpty(), "external input filter allowed");
                require(inventory.getStackInSlot(3).is(Items.DIRT), "external filter destination");
            }
            wrapper.getUpgradeHandler().setStackInSlot(0, ItemStack.EMPTY);
            wrapper.refreshInventoryForInputOutput();
            cached = getExternalHandler(entity);

            // 自动合成升级不能把整理中的铁锭转换成铁块。
            var compactItem = BuiltInRegistries.ITEM.getValue(Identifier.fromNamespaceAndPath("sophisticatedstorage", "compacting_upgrade"));
            require(compactItem != Items.AIR, "compacting upgrade registered");
            wrapper.getUpgradeHandler().setStackInSlot(0, new ItemStack(compactItem));
            try (var scope = new ConsolidationScope()) {
                require(insertRemainder(cached, 4, new ItemStack(Items.IRON_INGOT, 9), true).isEmpty(), "compacting insert");
                require(inventory.getStackInSlot(4).is(Items.IRON_INGOT)
                        && inventory.getStackInSlot(4).getCount() == 9, "compacting preserves items");
            }
            // 非整理操作不受禁止整理规则拦截，证明作用域没有泄漏。
            wrapper.getUpgradeHandler().setStackInSlot(0, ItemStack.EMPTY);
            noSort.selectSlot(4);
            require(extractStack(cached, 4, 1, true).getCount() == 1, "ordinary extraction restored");
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

    /** 用目标版本事务能力执行一次可提交或可回滚的定向插入。 */
    private static ItemStack insertRemainder(ResourceHandler<ItemResource> handler, int slot, ItemStack stack, boolean commit) {
        try (Transaction transaction = Transaction.openRoot()) {
            int inserted = handler.insert(slot, ItemResource.of(stack), stack.getCount(), transaction);
            if (commit) {
                transaction.commit();
            }
            return stack.copyWithCount(stack.getCount() - inserted);
        }
    }

    /** 用目标版本事务能力执行一次可提交或可回滚的定向抽取。 */
    private static ItemStack extractStack(ResourceHandler<ItemResource> handler, int slot, int amount, boolean commit) {
        ItemResource resource = handler.getResource(slot);
        try (Transaction transaction = Transaction.openRoot()) {
            int extracted = handler.extract(slot, resource, amount, transaction);
            if (commit) {
                transaction.commit();
            }
            return resource.toStack(extracted);
        }
    }

    /** 从真实箱子重新取得当前输入输出处理器，覆盖升级刷新后的包装变化。 */
    @SuppressWarnings("unchecked")
    private static ResourceHandler<ItemResource> getExternalHandler(Object entity) throws ReflectiveOperationException {
        return (ResourceHandler<ItemResource>) entity.getClass()
                .getMethod("getExternalItemHandler", net.minecraft.core.Direction.class)
                .invoke(entity, net.minecraft.core.Direction.DOWN);
    }
}
