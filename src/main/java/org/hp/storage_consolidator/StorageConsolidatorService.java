package org.hp.storage_consolidator;

import com.tom.storagemod.block.entity.StorageTerminalBlockEntity;
import com.tom.storagemod.inventory.IInventoryAccess;
import com.tom.storagemod.inventory.MultiInventoryAccess;
import com.tom.storagemod.inventory.PlatformFilteredInventoryAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;
import org.hp.storage_consolidator.access.TomStorageBlockPositionAccess;
import org.hp.storage_consolidator.access.TomStorageProxyAccess;
import org.hp.storage_consolidator.access.TomStorageTerminalAccess;
import org.hp.storage_consolidator.access.TomStorageTerminalMenuAccess;
import org.hp.storage_consolidator.access.SidedInventoryAccess;
import org.hp.storage_consolidator.access.CachedInventoryAccess;
import org.hp.storage_consolidator.access.FilteredInventoryAccess;
import org.hp.storage_consolidator.access.SophisticatedInventoryAccess;
import org.hp.storage_consolidator.compat.ConsolidationScope;
import net.neoforged.neoforge.items.wrapper.SidedInvWrapper;
import net.neoforged.neoforge.items.wrapper.InvWrapper;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/**
 * 在服务端执行 Tom's Storage 的物理槽位整理。
 */
public final class StorageConsolidatorService {
    private static ConsolidationJob activeJob;
    private static long serverTickStarted;

    /**
     * 记录服务端前置事件时间；前后事件间隔还包含其他模组的工作。
     */
    public static void onServerTickPre(ServerTickEvent.Pre event) {
        serverTickStarted = System.nanoTime();
    }

    private StorageConsolidatorService() {
    }

    /**
     * 验证当前终端并整理所有可安全识别的库存来源。
     */
    public static void consolidate(ServerPlayer player) {
        Storage_consolidator.LOGGER.info("Storage consolidation request: player={}, menu={}", player.getGameProfile().getName(), player.containerMenu.getClass().getName());
        if (!(player.containerMenu instanceof TomStorageTerminalMenuAccess menuAccess)) {
            Storage_consolidator.LOGGER.info("Storage consolidation request rejected: reason=not_terminal_menu");
            return;
        }

        StorageTerminalBlockEntity terminal = menuAccess.storageConsolidator$getTerminal();
        if (terminal == null || !(terminal instanceof TomStorageTerminalAccess terminalAccess)) {
            return;
        }
        if (!(terminal.getLevel() instanceof ServerLevel level)) {
            return;
        }
        if (!terminal.canInteractWith(player, true)) {
            Storage_consolidator.LOGGER.info("Storage consolidation request rejected: reason=cannot_interact");
            return;
        }

        IInventoryAccess networkAccess = terminalAccess.storageConsolidator$getInventoryAccess();
        if (networkAccess == null) {
            player.displayClientMessage(Component.translatable("message.storage_consolidator.no_network"), true);
            return;
        }

        // 全服务器只执行一个任务，避免多个玩家叠加每 tick 预算。
        if (activeJob != null) {
            Storage_consolidator.LOGGER.info("Storage consolidation request rejected: reason=busy, stage={}, ticks={}, moved={}", activeJob.stage, activeJob.ticks, activeJob.moved);
            player.displayClientMessage(Component.translatable("message.storage_consolidator.busy"), true);
            return;
        }
        activeJob = new ConsolidationJob(player, terminal, level, networkAccess);
        Storage_consolidator.LOGGER.info("Storage consolidation started: player={}, terminal={}, mode=continuous, yieldMs=3000",
                player.getGameProfile().getName(), terminal.getBlockPos());
        player.displayClientMessage(Component.translatable("message.storage_consolidator.started"), true);
    }

    /**
     * 在下一次服务端回调中连续整理，界面关闭不会取消已提交任务。
     */
    public static void onServerTick(ServerTickEvent.Post event) {
        ConsolidationJob job = activeJob;
        if (job == null) {
            return;
        }
        long start = System.nanoTime();
        // 保存调用前状态，所有详细文本都在工作预算结束后输出。
        long gap = job.previousCallback == 0 ? 0 : start - job.previousCallback;
        job.previousCallback = start;
        int stageBefore = job.stage;
        int scannedBefore = job.slotOrder;
        long movedBefore = job.moved;
        try {
            // 玩家离线、终端卸载或网络被替换时丢弃旧引用。
            if (event.getServer().getPlayerList().getPlayer(job.player.getUUID()) != job.player
                    || !job.level.isLoaded(job.terminal.getBlockPos())
                    || job.terminal.isRemoved()
                    || job.ticks % 20 == 0
                    && ((TomStorageTerminalAccess) job.terminal).storageConsolidator$getInventoryAccess() != job.root) {
                job.player.displayClientMessage(Component.translatable("message.storage_consolidator.cancelled"), true);
                activeJob = null;
                Storage_consolidator.LOGGER.info(
                        "Storage consolidation cancelled by validation: player={}, terminal={}, stage={}, ticks={}, moved={}",
                        job.player.getGameProfile().getName(), job.terminal.getBlockPos(), job.stage, job.ticks, job.moved);
                return;
            }
            long validated = System.nanoTime();
            TickBudget budget = new TickBudget(start);
            boolean finished;
            // 仅在整理调用内启用专用槽位规则，异常也会自动清理作用域。
            try (ConsolidationScope scope = new ConsolidationScope()) {
                finished = job.process(budget);
            }
            long processed = System.nanoTime();
            job.ticks++;
            // 连续处理结束后统一记录耗时，避免逐 tick 拆分。
            Storage_consolidator.LOGGER.info("Storage consolidation batch: checks={}, transfers={}, elapsedMs={}, finished={}",
                    budget.checks, budget.transfers, (processed - start) / 1_000_000.0, finished);
            job.maxTickNanos = Math.max(job.maxTickNanos, processed - start);
            // 每五秒输出一次进度，包含实际执行次数，便于识别预算耗尽导致的停滞。
            if (System.nanoTime() - job.lastReport >= TimeUnit.SECONDS.toNanos(5)) {
                job.lastReport = System.nanoTime();
                Storage_consolidator.LOGGER.info("Storage consolidation counters: {}", job.diagnosticCounts);
                Storage_consolidator.LOGGER.info(
                        "Storage consolidation progress: stage={}, scanned={}, group={}/{}, source={}, candidate={}, moved={}, checksThisTick={}, transfersThisTick={}, ticks={}, maxTickUs={}",
                        job.stage, job.slotOrder, job.groupIndex, job.groups.size(), job.sourceIndex,
                        job.candidateIndex, job.moved, budget.checks, budget.transfers, job.ticks,
                        TimeUnit.NANOSECONDS.toMicros(job.maxTickNanos));
            }
            if (finished) {
                Storage_consolidator.LOGGER.info("Storage consolidation final counters: {}", job.diagnosticCounts);
                Storage_consolidator.LOGGER.info(
                        "Storage consolidation finished: moved={}, slots={}, ticks={}, elapsedMs={}, maxTickUs={}",
                        job.moved, job.slotOrder, job.ticks,
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - job.started),
                        TimeUnit.NANOSECONDS.toMicros(job.maxTickNanos));
                job.player.displayClientMessage(Component.translatable("message.storage_consolidator.completed", job.moved), true);
                activeJob = null;
            }
            // 三秒预算耗尽后只让出本次回调，保留任务状态并在后续 tick 继续搬运。
            if (!finished) {
                Storage_consolidator.LOGGER.info("Storage consolidation yielded after callback budget: moved={}, slots={}, elapsedMs={}, continuing=true",
                        job.moved, job.slotOrder, (processed - start) / 1_000_000.0);
            }
            job.previousLoggingNanos = System.nanoTime() - processed;
        } catch (RuntimeException exception) {
            activeJob = null;
            Storage_consolidator.LOGGER.error("Storage consolidation cancelled after inventory failure", exception);
            job.player.displayClientMessage(Component.translatable("message.storage_consolidator.cancelled"), true);
        }
    }

    /**
     * 服务端结束时释放任务持有的世界和玩家引用。
     */
    public static void onServerStopped(ServerStoppedEvent event) {
        if (activeJob != null) {
            Storage_consolidator.LOGGER.info("Storage consolidation released on server stop: ticks={}, moved={}",
                    activeJob.ticks, activeJob.moved);
        }
        activeJob = null;
        serverTickStarted = 0;
    }

    /**
     * 每次回调最多连续执行三秒，预算耗尽后保留进度并交给后续回调继续执行。
     */
    private static final class TickBudget {
        private final long deadline;
        private int checks;
        private int transfers;

        private TickBudget(long start) {
            deadline = start + TimeUnit.SECONDS.toNanos(3);
        }

        private boolean available() {
            return System.nanoTime() < deadline;
        }
    }

    /**
     * 递归展开一个 Tom's Storage 库存访问器。
     */
    private static void collectSources(
            ServerLevel level,
            IInventoryAccess access,
            List<InventorySource> result,
            Set<IInventoryAccess> seenAccess,
            Set<IItemHandler> seenHandlers
    ) {
        if (access == null || !seenAccess.add(access)) {
            return;
        }

        if (access instanceof MultiInventoryAccess multiInventoryAccess) {
            for (IInventoryAccess connected : multiInventoryAccess.getConnected()) {
                collectSources(level, connected, result, seenAccess, seenHandlers);
            }
            return;
        }

        IItemHandler handler;
        try {
            handler = access.getPlatformHandler();
        } catch (RuntimeException exception) {
            Storage_consolidator.LOGGER.debug("Skipped inventory source because its handler could not be read", exception);
            return;
        }
        if (handler == null || handler.getSlots() <= 0 || !seenHandlers.add(handler)) {
            diagnostic("source_empty_or_duplicate_handler", null, null);
            return;
        }

        BlockPos position = findPosition(access);
        if (position == null && Config.skipUnknownSources) {
            diagnostic("source_unknown_position", null, null);
            Storage_consolidator.LOGGER.debug("Skipped an inventory source with no resolvable block position");
            return;
        }
        if (position != null && !level.isLoaded(position)) {
            diagnostic("source_unloaded", new SlotRef(handler, position, 0, -1), null);
            Storage_consolidator.LOGGER.debug("Skipped an unloaded inventory source at {}", position);
            return;
        }

        if (position != null) {
            ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(level.getBlockState(position).getBlock());
            if (blockId != null && Config.blockedSourceModIds.contains(blockId.getNamespace())) {
                diagnostic("source_blocked_namespace", new SlotRef(handler, position, 0, -1), null);
                Storage_consolidator.LOGGER.debug("Skipped blocked inventory source {} at {}", blockId, position);
                return;
            }
        }

        result.add(new InventorySource(handler, position));
        // 逐条记录来源的真实位置和处理器，定位 Tom 网络是否暴露了额外库存。
        ResourceLocation discoveredBlock = position == null
                ? null
                : BuiltInRegistries.BLOCK.getKey(level.getBlockState(position).getBlock());
        Object discoveredEntity = position == null || !level.isLoaded(position)
                ? null
                : level.getBlockEntity(position);
        Storage_consolidator.LOGGER.info(
                "Storage consolidation source discovered: index={}, pos={}, block={}, entity={}, access={}@{}, handler={}@{}, slots={}",
                result.size() - 1,
                position,
                discoveredBlock,
                discoveredEntity == null
                        ? "none"
                        : discoveredEntity.getClass().getName() + "@" + System.identityHashCode(discoveredEntity),
                access.getClass().getName(),
                System.identityHashCode(access),
                handler.getClass().getName(),
                System.identityHashCode(handler),
                handler.getSlots()
        );
        // 记录访问包装及同坐标多处理器，坐标相同只作为别名线索，不直接判定同槽。
        if (activeJob != null) {
            SlotRef identity = new SlotRef(handler, position, 0, result.size() - 1);
            if (position != null) {
                IItemHandler previous = activeJob.diagnosticPositions.putIfAbsent(position, handler);
                if (previous != null && previous != handler) {
                    diagnostic("source_multiple_handlers_same_position", identity, new SlotRef(previous, position, 0, -1));
                }
            }
            diagnostic("source_accepted_" + access.getClass().getSimpleName(), identity, null);
        }
    }

    /**
     * 从包装访问器中追溯真实库存方块位置。
     */
    private static BlockPos findPosition(IInventoryAccess access) {
        Set<IInventoryAccess> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        return findPosition(access, seen);
    }

    /**
     * 递归追踪过滤器和代理访问器的底层来源。
     */
    private static BlockPos findPosition(IInventoryAccess access, Set<IInventoryAccess> seen) {
        if (access == null || !seen.add(access)) {
            return null;
        }
        if (access instanceof TomStorageBlockPositionAccess positionAccess) {
            return positionAccess.storageConsolidator$getPosition();
        }
        if (access instanceof PlatformFilteredInventoryAccess filteredAccess) {
            return findPosition(filteredAccess.getActualInventory(), seen);
        }
        if (access instanceof TomStorageProxyAccess proxyAccess) {
            return findPosition(proxyAccess.storageConsolidator$getWrappedAccess(), seen);
        }
        return null;
    }

    /**
     * 用显式游标拆分网络展开、槽位分组、候选搜索及搬运。
     */
    private static final class ConsolidationJob {
        private final ServerPlayer player;
        private final StorageTerminalBlockEntity terminal;
        private final ServerLevel level;
        private final IInventoryAccess root;
        private final long started = System.nanoTime();
        private long lastReport = started;
        // 事件计数始终保留，详细样本按次数采样，避免长期任务无限刷屏。
        private final Map<String, Long> diagnosticCounts = new java.util.LinkedHashMap<>();
        private final Map<BlockPos, IItemHandler> diagnosticPositions = new java.util.HashMap<>();
        private long transferSequence;
        private SlotRef previousSource;
        private SlotRef previousTarget;
        private int repeatedPair;
        private long previousCallback;
        private long previousLoggingNanos;
        private long maxTickNanos;
        private int ticks;
        private long moved;
        private int slotOrder;
        private final Deque<Iterator<IInventoryAccess>> pending = new ArrayDeque<>();
        private final Set<IInventoryAccess> seenAccess = Collections.newSetFromMap(new IdentityHashMap<>());
        private final Set<IItemHandler> seenHandlers = Collections.newSetFromMap(new IdentityHashMap<>());
        private final List<InventorySource> sources = new ArrayList<>();
        // 保存方块实体身份，避免原容器被替换后继续操作旧处理器。
        private final Map<BlockPos, net.minecraft.world.level.block.entity.BlockEntity> sourceEntities = new java.util.HashMap<>();
        private final Map<Item, List<ItemGroup>> byItem = new IdentityHashMap<>();
        private final List<ItemGroup> groups = new ArrayList<>();
        private final TargetIndex emptyIndex = new TargetIndex();
        private final Map<IItemHandler, Boolean> voidCache = new IdentityHashMap<>();
        private int sourceScan;
        private int slotScan;
        private int groupIndex;
        private int sourceIndex;
        private int candidateIndex;
        private int stage;
        private boolean searchingEmpty;
        private SlotRef currentSource;
        private ItemStack currentStack;
        private TargetCandidate best;
        // 每组候选只建立一次；后续来源复用候选，避免每搬一堆重扫整组。
        private final java.util.PriorityQueue<TargetCandidate> rankedTargets =
                new java.util.PriorityQueue<>(targetComparator());
        private final List<TargetCandidate> deferredTargets = new ArrayList<>();
        private boolean indexReady;
        private boolean restoringTargets;
        private final Set<PhysicalSlot> destinations = new java.util.HashSet<>();

        private ConsolidationJob(ServerPlayer player, StorageTerminalBlockEntity terminal,
                                 ServerLevel level, IInventoryAccess root) {
            this.player = player;
            this.terminal = terminal;
            this.level = level;
            this.root = root;
            pending.push(List.of(root).iterator());
        }

        /**
         * 每轮只前进一步，任何暂停都保留当前阶段和所有下标。
         */
        private boolean process(TickBudget budget) {
            while (budget.available()) {
                budget.checks++;
                // 依次展开网络、建立物品索引并直接执行搬运。
                // 第一阶段逐个展开访问器，避免递归一次遍历整个网络。
                if (stage == 0) {
                    if (pending.isEmpty()) {
                        stage = 1;
                        continue;
                    }
                    Iterator<IInventoryAccess> iterator = pending.peek();
                    if (!iterator.hasNext()) {
                        pending.pop();
                        continue;
                    }
                    IInventoryAccess access = iterator.next();
                    if (access instanceof MultiInventoryAccess multi) {
                        if (seenAccess.add(access)) {
                            // Tom 会在后续 tick 重建原列表，只遍历本次取得的独立快照。
                            List<IInventoryAccess> snapshot = new ArrayList<>(multi.getConnected());
                            // 记录每个 Tom 多库存节点展开了多少个子访问器，区分嵌套网络节点。
                            Storage_consolidator.LOGGER.info(
                                    "Storage consolidation network snapshot: access={}@{}, connectedCount={}, snapshotSize={}",
                                    multi.getClass().getName(),
                                    System.identityHashCode(multi),
                                    multi.getConnected().size(),
                                    snapshot.size()
                            );
                            pending.push(snapshot.iterator());
                            diagnostic("network_snapshot_captured", null, null);
                        }
                    } else {
                        int previousSize = sources.size();
                        collectSources(level, access, sources, seenAccess, seenHandlers);
                        if (sources.size() > previousSize) {
                            InventorySource added = sources.getLast();
                            if (added.position() != null) {
                                sourceEntities.put(added.position(), level.getBlockEntity(added.position()));
                            }
                        }
                    }
                    continue;
                }
                // 第二阶段逐槽读取并建立分组，不在一次 tick 内复制整张库存。
                if (stage == 1) {
                    if (sourceScan >= sources.size()) {
                        stage = 2;
                        continue;
                    }
                    InventorySource inventory = sources.get(sourceScan);
                    if (slotScan >= inventory.handler().getSlots()) {
                        sourceScan++;
                        slotScan = 0;
                        continue;
                    }
                    SlotRef slot = new SlotRef(inventory.handler(), inventory.position(), slotScan++, slotOrder++);
                    ItemStack stack = readStack(slot);
                    if (stack.isEmpty()) {
                        emptyIndex.registerEmpty(slot);
                        continue;
                    }
                    List<ItemGroup> variants = byItem.computeIfAbsent(stack.getItem(), ignored -> new ArrayList<>());
                    ItemGroup group = null;
                    for (ItemGroup variant : variants) {
                        if (ItemStack.isSameItemSameComponents(variant.template(), stack)) {
                            group = variant;
                            break;
                        }
                    }
                    if (group == null) {
                        group = new ItemGroup(stack.copyWithCount(1));
                        groups.add(group);
                        variants.add(group);
                    }
                    group.slots().add(slot);
                    group.members().add(physicalSlot(slot));
                    continue;
                }
                // 第三阶段逐个检查候选，保存当前最优目标，避免不可中断的批量排序。
                if (groupIndex >= groups.size()) {
                    return true;
                }
                ItemGroup group = groups.get(groupIndex);
                if (group.members().size() <= 1 || group.template().getMaxStackSize() <= 1
                        || sourceIndex >= group.slots().size()) {
                    groupIndex++;
                    sourceIndex = 0;
                    currentSource = null;
                    destinations.clear();
                    rankedTargets.clear();
                    deferredTargets.clear();
                    indexReady = false;
                    continue;
                }
                if (currentSource == null) {
                    currentSource = group.slots().get(sourceIndex);
                    currentStack = readStack(currentSource);
                    candidateIndex = 0;
                    searchingEmpty = false;
                    best = null;
                    restoringTargets = true;
                    if (destinations.contains(physicalSlot(currentSource)) || currentStack.isEmpty()
                            || !ItemStack.isSameItemSameComponents(currentStack, group.template())) {
                        sourceIndex++;
                        currentSource = null;
                    }
                    continue;
                }
                List<SlotRef> candidates = searchingEmpty ? emptyIndex.emptySlots() : group.slots();
                // 延后候选逐个恢复，避免一次性批量入队突破检查预算。
                if (restoringTargets && !deferredTargets.isEmpty()) {
                    rankedTargets.add(deferredTargets.removeLast());
                    continue;
                }
                restoringTargets = false;
                if (!indexReady && candidateIndex < candidates.size()) {
                    SlotRef candidate = candidates.get(candidateIndex++);
                    List<TargetCandidate> found = new ArrayList<>(1);
                    // void 状态在候选检查时重新读取，不跨 tick 缓存升级状态。
                    voidCache.clear();
                    addTargetCandidate(group, currentSource, currentStack, candidate, found, voidCache);
                    if (!found.isEmpty()) {
                        rankedTargets.add(found.getFirst());
                    }
                    continue;
                }
                if (!indexReady && rankedTargets.isEmpty() && !searchingEmpty) {
                    searchingEmpty = true;
                    candidateIndex = 0;
                    continue;
                }
                indexReady = true;
                // 每次检查一个缓存候选，失效候选移除，来源特有过滤失败留给后续来源。
                if (best == null && !rankedTargets.isEmpty()) {
                    TargetCandidate candidate = rankedTargets.poll();
                    ItemStack actualSource = readStack(currentSource);
                    ItemStack actualTarget = readStack(candidate.slot());
                    if (actualSource.isEmpty() || !ItemStack.isSameItemSameComponents(actualSource, group.template())) {
                        sourceIndex++;
                        currentSource = null;
                        rankedTargets.add(candidate);
                        continue;
                    }
                    if (sameSlot(currentSource, candidate.slot())) {
                        deferredTargets.add(candidate);
                        continue;
                    }
                    if (!actualTarget.isEmpty() && !ItemStack.isSameItemSameComponents(actualTarget, group.template())
                            || actualTarget.getCount() >= effectiveStackLimit(candidate.slot(), group.template())) {
                        continue;
                    }
                    voidCache.clear();
                    if (hasVoidUpgrade(candidate.slot().handler(), voidCache)) {
                        continue;
                    }
                    if (previewInsert(candidate.slot(), actualSource) <= 0) {
                        deferredTargets.add(candidate);
                        continue;
                    }
                    best = candidate;
                    continue;
                }
                if (best != null) {
                    // 直接执行当前搬运事务，保留容器过滤和升级检查。
                    ItemStack actual = readStack(currentSource);
                    ItemStack target = readStack(best.slot());
                    voidCache.clear();
                    if (ItemStack.isSameItemSameComponents(actual, group.template())
                            && (target.isEmpty() || ItemStack.isSameItemSameComponents(actual, target))
                            && !hasVoidUpgrade(best.slot().handler(), voidCache)) {
                        budget.transfers++;
                        int count = transfer(player, currentSource, best.slot(), actual);
                        moved += count;
                        if (count > 0) {
                            destinations.add(physicalSlot(best.slot()));
                            // 更新目标实际数量后重新排队，维持容量及数量优先级。
                            ItemStack remainingTarget = readStack(best.slot());
                            int limit = effectiveStackLimit(best.slot(), group.template());
                            if (remainingTarget.getCount() < limit) {
                                rankedTargets.add(new TargetCandidate(best.slot(), true, limit,
                                        remainingTarget.getCount(), limit - remainingTarget.getCount()));
                            }
                            // 同一来源尚有剩余时重新搜索，但保留已接收槽位的身份。
                            if (!readStack(currentSource).isEmpty()) {
                                currentSource = null;
                                continue;
                            }
                        }
                    }
                }
                emptyIndex.registerEmptyIfEmpty(currentSource);
                sourceIndex++;
                currentSource = null;
            }
            return false;
        }
    }

    /**
     * 检查单个目标槽位并加入候选列表，统一处理过滤器、堆叠上限和 void 升级保护。
     */
    private static void addTargetCandidate(
            ItemGroup group,
            SlotRef source,
            ItemStack sourceStack,
            SlotRef candidate,
            List<TargetCandidate> targets,
            Map<IItemHandler, Boolean> voidUpgradeCache
    ) {
            if (sameSlot(source, candidate)) {
                diagnostic("candidate_same_physical_slot", source, candidate);
                return;
            }

            ItemStack targetStack = readStack(candidate);
            boolean sameItem = !targetStack.isEmpty()
                    && ItemStack.isSameItemSameComponents(group.template(), targetStack);
            if (!targetStack.isEmpty() && !sameItem) {
                diagnostic("candidate_different_components", source, candidate);
                return;
            }
            // 满槽无需模拟插入；大型网络整理后大多数候选都属于此类。
            int limit = effectiveStackLimit(candidate, group.template());
            if (!targetStack.isEmpty() && targetStack.getCount() >= limit) {
                diagnostic("candidate_full", source, candidate);
                return;
            }
            // 已经参与本组整理的来源槽位清空后不能重新作为空目标，否则可能把物品重新分散回去。
            if (targetStack.isEmpty() && group.members().contains(physicalSlot(candidate))) {
                diagnostic("candidate_emptied_source", source, candidate);
                return;
            }
            if (targetStack.isEmpty() && !isValidTarget(candidate, group.template())) {
                diagnostic("candidate_filter_rejected", source, candidate);
                return;
            }
            // Sophisticated Storage 的 void 升级会把插入结果报告为空栈，但实际不保存物品；检测到后跳过该目标。
            if (hasVoidUpgrade(candidate.handler(), voidUpgradeCache)) {
                diagnostic("candidate_void_or_unknown_upgrade", source, candidate);
                Storage_consolidator.LOGGER.debug(
                        "Skipped a Sophisticated Storage target slot with a void upgrade: slot={}",
                        candidate.index()
                );
                return;
            }

            int accepted = previewInsert(candidate, sourceStack);
            if (accepted <= 0) {
                diagnostic("candidate_simulated_insert_rejected", source, candidate);
                return;
            }

            int count = targetStack.isEmpty() ? 0 : targetStack.getCount();
            targets.add(new TargetCandidate(candidate, sameItem, limit, count, Math.max(0, limit - count)));
    }

    /**
     * 缓存目标处理器的 void 升级检测结果，避免整理大量槽位时重复反射查询。
     */
    private static boolean hasVoidUpgrade(IItemHandler handler, Map<IItemHandler, Boolean> cache) {
        return cache.computeIfAbsent(handler, StorageConsolidatorService::detectVoidUpgrade);
    }

    /**
     * 通过 Sophisticated Core 当前版本的真实内部 API 检查库存是否安装了 void 升级。
     */
    private static boolean detectVoidUpgrade(IItemHandler handler) {
        IItemHandler inspectedHandler;
        try {
            inspectedHandler = unwrapFilteredHandler(handler);
        } catch (RuntimeException exception) {
            Storage_consolidator.LOGGER.debug(
                    "Could not unwrap an inventory handler for void upgrade inspection; skipping it as a safety measure",
                    exception
            );
            return true;
        }
        Class<?> currentType = inspectedHandler.getClass();
        // 已安装适配的库存会在整理期间抑制销毁回调，可正常参与搬运。
        if (inspectedHandler instanceof SophisticatedInventoryAccess) return false;
        boolean isSophisticatedHandler = false;
        while (currentType != null) {
            if (currentType.getName().equals("net.p3pp3rf1y.sophisticatedcore.inventory.InventoryHandler")) {
                isSophisticatedHandler = true;
                break;
            }
            currentType = currentType.getSuperclass();
        }
        if (!isSophisticatedHandler) {
            return false;
        }

        currentType = inspectedHandler.getClass();
        while (currentType != null) {
            try {
                Method hasVoidUpgrade = currentType.getDeclaredMethod("hasVoidUpgrade");
                hasVoidUpgrade.setAccessible(true);
                return Boolean.TRUE.equals(hasVoidUpgrade.invoke(inspectedHandler));
            } catch (NoSuchMethodException ignored) {
                currentType = currentType.getSuperclass();
            } catch (ReflectiveOperationException | RuntimeException exception) {
                Storage_consolidator.LOGGER.debug(
                        "Could not inspect Sophisticated Storage void upgrade state; skipping the handler as a safety measure",
                        exception
                );
                return true;
            }
        }

        Storage_consolidator.LOGGER.debug(
                "Sophisticated Storage handler has no recognizable void upgrade method; skipping it as a safety measure"
        );
        return true;
    }

    /**
     * 解除 Tom's Storage 为过滤器创建的处理器包装，以便检查底层真实库存类型。
     */
    private static IItemHandler unwrapFilteredHandler(IItemHandler handler) {
        IItemHandler current = handler;
        while (true) {
            // 过滤包装不改变槽位索引；原外层仍负责拦截实际插入与抽取。
            if (current instanceof FilteredInventoryAccess filtered) {
                IItemHandler actual = filtered.storageConsolidator$getInventory();
                if (actual == null || actual == current) return current;
                current = actual;
                continue;
            }
            // 缓存包装不改变槽位映射，只用于解析身份与容量。
            if (current instanceof CachedInventoryAccess cached) {
                IItemHandler actual = cached.storageConsolidator$getWrappedHandler().get();
                if (actual == null || actual == current) return current;
                current = actual;
                continue;
            }
            if (!(current instanceof PlatformFilteredInventoryAccess filteredAccess)) return current;
            Object actual = filteredAccess.getActualInventory().getPlatformHandler();
            if (!(actual instanceof IItemHandler actualHandler) || actualHandler == current) {
                return current;
            }
            current = actualHandler;
        }
    }

    /**
     * 按同类堆叠、堆叠上限、当前数量和剩余空间排序目标槽位。
     */
    private static Comparator<TargetCandidate> targetComparator() {
        return Comparator.comparingInt((TargetCandidate target) -> target.sameItem() ? 1 : 0).reversed()
                .thenComparing(Comparator.comparingInt(TargetCandidate::limit).reversed())
                .thenComparing(Comparator.comparingInt(TargetCandidate::count).reversed())
                .thenComparing(Comparator.comparingInt(TargetCandidate::free).reversed())
                .thenComparingInt(target -> target.slot().order());
    }

    /**
     * 检查空槽位是否接受目标物品，并保留容器自身的过滤规则。
     */
    private static boolean isValidTarget(SlotRef target, ItemStack template) {
        try {
            return target.handler().isItemValid(target.index(), template);
        } catch (RuntimeException exception) {
            Storage_consolidator.LOGGER.debug("Skipped a target slot because its validity check failed", exception);
            return false;
        }
    }

    /**
     * 使用模拟插入计算目标槽位本次最多能接收多少物品。
     */
    private static int previewInsert(SlotRef target, ItemStack sourceStack) {
        int limit = effectiveStackLimit(target, sourceStack);
        if (limit <= 0) {
            return 0;
        }
        int amount = Math.min(sourceStack.getCount(), sourceStack.getMaxStackSize());
        if (amount <= 0) {
            return 0;
        }
        try {
            int requested = Math.min(amount, limit);
            ItemStack remainder = target.handler().insertItem(
                    target.index(),
                    sourceStack.copyWithCount(requested),
                    true
            );
            return requested - remainder.getCount();
        } catch (RuntimeException exception) {
            Storage_consolidator.LOGGER.debug("Skipped a target slot because its simulated insertion failed", exception);
            return 0;
        }
    }

    /**
     * 计算同时受物品和目标槽位限制的实际堆叠上限。
     */
    private static int effectiveStackLimit(SlotRef target, ItemStack template) {
        try {
            // 容量升级可超过物品默认堆叠数，使用上游实际槽位上限。
            IItemHandler inner = unwrapFilteredHandler(target.handler());
            if (inner instanceof SophisticatedInventoryAccess access) {
                return access.storageConsolidator$canSort(target.index())
                        ? access.storageConsolidator$stackLimit(target.index(), template) : 0;
            }
            return Math.min(target.handler().getSlotLimit(target.index()), template.getMaxStackSize());
        } catch (RuntimeException exception) {
            Storage_consolidator.LOGGER.debug("Could not read a target slot limit", exception);
            return 0;
        }
    }

    /**
     * 先模拟抽取和插入，再提交实际变化；异常时把剩余物品退回玩家，避免静默丢失。
     */
    private static int transfer(ServerPlayer player, SlotRef source, SlotRef target, ItemStack sourceStack) {
        // 提交之前再次检查物理身份，即使候选来自不同方向包装也不能向自身搬运。
        if (sameSlot(source, target)) {
            diagnostic("transfer_same_physical_slot_blocked", source, target);
            return 0;
        }
        if (!isLive(source) || !isLive(target)) {
            diagnostic("transfer_stale_endpoint", source, target);
            return 0;
        }
        // 观察值均为副本；额外读取不参与搬运决策。
        long transferStarted = System.nanoTime();
        ItemStack sourceBefore = readStack(source);
        ItemStack targetBefore = readStack(target);
        int possible = previewInsert(target, sourceStack);
        if (possible <= 0) {
            diagnostic("transfer_simulated_insert_rejected", source, target);
            return 0;
        }

        ItemStack extractedPreview;
        try {
            extractedPreview = source.handler().extractItem(source.index(), possible, true);
        } catch (RuntimeException exception) {
            Storage_consolidator.LOGGER.debug("Skipped a source slot because its simulated extraction failed", exception);
            diagnostic("transfer_simulated_extract_exception", source, target);
            return 0;
        }
        if (extractedPreview.isEmpty()) {
            diagnostic("transfer_simulated_extract_empty", source, target);
            return 0;
        }

        ItemStack extracted;
        try {
            extracted = source.handler().extractItem(source.index(), extractedPreview.getCount(), false);
        } catch (RuntimeException exception) {
            Storage_consolidator.LOGGER.debug("Skipped a source slot because its extraction failed", exception);
            diagnostic("transfer_actual_extract_exception", source, target);
            return 0;
        }
        if (extracted.isEmpty()) {
            diagnostic("transfer_actual_extract_empty", source, target);
            return 0;
        }

        ItemStack sourceAfterExtract = readStack(source);
        ItemStack targetAfterExtract = readStack(target);
        ItemStack remainder;
        try {
            remainder = target.handler().insertItem(target.index(), extracted.copy(), false);
        } catch (RuntimeException exception) {
            player.getInventory().placeItemBackInInventory(extracted);
            Storage_consolidator.LOGGER.error("Target insertion failed after extraction; returned items to player", exception);
            diagnostic("transfer_insert_exception_returned_to_player", source, target);
            return 0;
        }

        int inserted = extracted.getCount() - remainder.getCount();
        if (!remainder.isEmpty()) {
            player.getInventory().placeItemBackInInventory(remainder);
            Storage_consolidator.LOGGER.error("A target accepted only part of a simulated transfer; returned the remainder to player");
            diagnostic("transfer_partial_insert_returned_to_player", source, target);
        }
        // 对比返回值和真实槽位变化，识别同库存别名、无变化搬运和组件变化。
        ItemStack sourceAfter = readStack(source);
        ItemStack targetAfter = readStack(target);
        boolean unchanged = sourceBefore.getCount() == sourceAfter.getCount()
                && targetBefore.getCount() == targetAfter.getCount();
        boolean mismatch = sourceBefore.getCount() - sourceAfter.getCount() != extracted.getCount()
                || targetAfter.getCount() - targetBefore.getCount() != inserted;
        if (unchanged && inserted > 0) diagnostic("transfer_claimed_success_no_change", source, target);
        if (mismatch) diagnostic("transfer_observed_delta_mismatch", source, target);
        if (!extracted.isEmpty() && !ItemStack.isSameItemSameComponents(sourceBefore, extracted))
            diagnostic("transfer_extracted_components_changed", source, target);
        if (activeJob != null) {
            ConsolidationJob job = activeJob;
            long sequence = ++job.transferSequence;
            boolean samePair = job.previousSource != null && sameSlot(job.previousSource, source)
                    && sameSlot(job.previousTarget, target);
            boolean reversed = job.previousSource != null && sameSlot(job.previousSource, target)
                    && sameSlot(job.previousTarget, source);
            job.repeatedPair = samePair ? job.repeatedPair + 1 : 1;
            job.previousSource = source;
            job.previousTarget = target;
            if (reversed) diagnostic("transfer_reverse_pair", source, target);
            if (job.repeatedPair > 1) diagnostic("transfer_repeated_pair", source, target);
            // 正常前六十四笔及后续每千笔采样；异常样本由事件采样器单独保留。
            if (sequence <= 64 || sequence % 1024 == 0
                    || (mismatch && (job.repeatedPair <= 4 || (job.repeatedPair & (job.repeatedPair - 1)) == 0))) {
                Storage_consolidator.LOGGER.info(
                        "Storage consolidation transfer: seq={}, tick={}, group={}, sourceCursor={}, src={}, dst={}, item={}, componentsHash={}, requested={}, simulatedExtract={}, actualExtract={}, remainder={}, reportedInserted={}, srcCounts={}->{}->{}, dstCounts={}->{}->{}, unchanged={}, mismatch={}, repeated={}, reversed={}, elapsedUs={}",
                        sequence, job.ticks, job.groupIndex, job.sourceIndex, describeSlot(source), describeSlot(target),
                        BuiltInRegistries.ITEM.getKey(sourceBefore.getItem()), sourceBefore.getComponents().hashCode(),
                        possible, extractedPreview.getCount(), extracted.getCount(), remainder.getCount(), inserted,
                        sourceBefore.getCount(), sourceAfterExtract.getCount(), sourceAfter.getCount(),
                        targetBefore.getCount(), targetAfterExtract.getCount(), targetAfter.getCount(),
                        unchanged, mismatch, job.repeatedPair, reversed, (System.nanoTime() - transferStarted) / 1000.0);
            }
        }
        // 返回成功但槽位数量不符时停止，避免继续扩大未知库存变化。
        if (mismatch) {
            throw new IllegalStateException("Inventory transfer delta mismatch; consolidation stopped");
        }
        return Math.max(0, inserted);
    }

    /**
     * 按原因累计诊断事件，并输出受限数量的详细样本。
     */
    private static void diagnostic(String reason, SlotRef source, SlotRef target) {
        ConsolidationJob job = activeJob;
        if (job == null) return;
        long count = job.diagnosticCounts.merge(reason, 1L, Long::sum);
        // 各原因保留前四次和二次幂样本，累计数量不受输出限额影响。
        if (count <= 4 || (count & (count - 1)) == 0) {
            Storage_consolidator.LOGGER.info(
                    "Storage consolidation event: reason={}, count={}, tick={}, stage={}, sourceCursor={}, src={}, dst={}",
                    reason, count, job.ticks, job.stage, job.sourceIndex, describeSlot(source), describeSlot(target));
        }
    }

    /**
     * 输出外层与已知过滤包装底层身份，不能解析的包装如实标为未知。
     */
    private static String describeSlot(SlotRef slot) {
        if (slot == null) return "none";
        try {
            IItemHandler inner = unwrapFilteredHandler(slot.handler());
            // 输出真实库存身份和槽位，便于核对不同方向包装是否指向同一位置。
            PhysicalSlot physical = physicalSlot(slot);
            Object entity = activeJob == null || slot.position() == null
                    || !activeJob.level.isLoaded(slot.position()) ? null : activeJob.level.getBlockEntity(slot.position());
            return "pos=" + slot.position() + "/slot=" + slot.index() + "/order=" + slot.order()
                    + "/handler=" + slot.handler().getClass().getName() + "@" + System.identityHashCode(slot.handler())
                    + "/unwrapped=" + inner.getClass().getName() + "@" + System.identityHashCode(inner)
                    + "/physical=" + physical.inventory().getClass().getName() + "@"
                    + System.identityHashCode(physical.inventory()) + ":" + physical.index()
                    + "/entity=" + (entity == null ? "none" : entity.getClass().getName() + "@" + System.identityHashCode(entity))
                    + "/live=" + isLive(slot) + "/slotLimit=" + slot.handler().getSlotLimit(slot.index());
        } catch (RuntimeException exception) {
            return "pos=" + slot.position() + "/slot=" + slot.index() + "/diagnosticError=" + exception.getClass().getName();
        }
    }

    /**
     * 仅检查已加载区块中的原方块实体身份。
     */
    private static boolean isLive(SlotRef slot) {
        return activeJob == null || slot.position() == null
                || activeJob.level.isLoaded(slot.position())
                && activeJob.sourceEntities.get(slot.position()) != null
                && activeJob.level.getBlockEntity(slot.position()) == activeJob.sourceEntities.get(slot.position());
    }

    /**
     * 判断两个槽位是否指向同一个处理器和同一个槽位。
     */
    private static boolean sameSlot(SlotRef first, SlotRef second) {
        return physicalSlot(first).equals(physicalSlot(second));
    }

    /**
     * 方向包装索引必须经真实映射转换；物品操作仍经过原包装以保留方向和过滤限制。
     */
    private static PhysicalSlot physicalSlot(SlotRef slot) {
        IItemHandler handler = unwrapFilteredHandler(slot.handler());
        if (handler instanceof SidedInventoryAccess sided) {
            int index = SidedInvWrapper.getSlot(sided.storageConsolidator$getContainer(),
                    slot.index(), sided.storageConsolidator$getSide());
            if (index < 0) {
                throw new IllegalStateException("Invalid sided inventory slot mapping: " + slot.index());
            }
            return new PhysicalSlot(sided.storageConsolidator$getContainer(), index);
        }
        if (handler instanceof InvWrapper wrapper) {
            return new PhysicalSlot(wrapper.getInv(), slot.index());
        }
        return new PhysicalSlot(handler, slot.index());
    }

    /**
     * 库存对象按引用比较，避免不同库存的自定义相等实现误合并。
     */
    private record PhysicalSlot(Object inventory, int index) {
        @Override
        public boolean equals(Object other) {
            return other instanceof PhysicalSlot slot && inventory == slot.inventory && index == slot.index;
        }

        @Override
        public int hashCode() {
            return 31 * System.identityHashCode(inventory) + index;
        }
    }

    /**
     * 安全读取槽位，异常时按空槽处理并记录调试信息。
     */
    private static ItemStack readStack(SlotRef slot) {
        if (!isLive(slot)) {
            return ItemStack.EMPTY;
        }
        try {
            // 特殊分区和禁止整理槽位不进入来源或目标候选。
            IItemHandler inner = unwrapFilteredHandler(slot.handler());
            if (inner instanceof SophisticatedInventoryAccess access
                    && !access.storageConsolidator$canSort(slot.index())) return ItemStack.EMPTY;
            ItemStack stack = slot.handler().getStackInSlot(slot.index());
            return stack == null ? ItemStack.EMPTY : stack.copy();
        } catch (RuntimeException exception) {
            diagnostic("slot_read_exception", slot, null);
            Storage_consolidator.LOGGER.debug("Could not read an inventory slot", exception);
            return ItemStack.EMPTY;
        }
    }

    /**
     * 保存按物品本体索引的非空槽位，并动态登记整理过程中变为空的槽位。
     */
    private static final class TargetIndex {
        private final List<SlotRef> emptySlots = new ArrayList<>();
        private final Set<SlotRef> knownEmptySlots = Collections.newSetFromMap(new IdentityHashMap<>());

        private List<SlotRef> emptySlots() {
            return emptySlots;
        }

        /**
         * 登记一个当前为空的槽位，并避免同一槽位重复进入索引。
         */
        private void registerEmpty(SlotRef slot) {
            if (knownEmptySlots.add(slot)) {
                emptySlots.add(slot);
            }
        }

        /**
         * 只在槽位实际变空时更新索引，避免为非空来源制造无效候选。
         */
        private void registerEmptyIfEmpty(SlotRef slot) {
            if (readStack(slot).isEmpty()) {
                registerEmpty(slot);
            }
        }
    }

    private record InventorySource(IItemHandler handler, BlockPos position) {
    }


    private record SlotRef(IItemHandler handler, BlockPos position, int index, int order) {
    }

    private record TargetCandidate(SlotRef slot, boolean sameItem, int limit, int count, int free) {
    }

    private record ItemGroup(ItemStack template, List<SlotRef> slots, Set<PhysicalSlot> members) {
        private ItemGroup(ItemStack template) {
            this(template, new ArrayList<>(), new java.util.HashSet<>());
        }
    }
}
