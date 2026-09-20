package org.hp.storage_consolidator;

import com.tom.storagemod.gui.StorageTerminalMenu;
import com.tom.storagemod.tile.StorageTerminalBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.items.IItemHandler;
import org.hp.storage_consolidator.access.TomStorageTerminalAccess;
import org.hp.storage_consolidator.compat.ConsolidationScope;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** 在服务端使用 Tom's Storage 的合并库存处理器执行物理槽位整理。 */
public final class StorageConsolidatorService {
    /** 全服务器唯一的进行中任务。 */
    private static ConsolidationJob activeJob;

    /** 禁止实例化服务类。 */
    private StorageConsolidatorService() {
    }

    /** 验证终端、距离和菜单后提交一个持续整理任务。 */
    public static void consolidate(ServerPlayer player) {
        Storage_consolidator.LOGGER.info("Storage consolidation request: player={}, menu={}",
                player.getGameProfile().getName(), player.containerMenu.getClass().getName());
        if (!(player.containerMenu instanceof StorageTerminalMenu menu)) {
            Storage_consolidator.LOGGER.info("Storage consolidation request rejected: reason=not_terminal_menu");
            return;
        }
        StorageTerminalBlockEntity terminal = menu.getTerminal();
        if (terminal == null || !(terminal instanceof TomStorageTerminalAccess access)) {
            Storage_consolidator.LOGGER.info("Storage consolidation request rejected: reason=missing_terminal_access");
            return;
        }
        if (!(terminal.getLevel() instanceof ServerLevel level)) {
            Storage_consolidator.LOGGER.info("Storage consolidation request rejected: reason=not_server_level");
            return;
        }
        if (!terminal.canInteractWith(player)) {
            Storage_consolidator.LOGGER.info("Storage consolidation request rejected: reason=cannot_interact");
            return;
        }
        IItemHandler network = access.storageConsolidator$getItemHandler();
        if (network == null || network.getSlots() <= 0) {
            player.displayClientMessage(Component.translatable("message.storage_consolidator.no_network"), true);
            return;
        }
        if (activeJob != null) {
            Storage_consolidator.LOGGER.info("Storage consolidation request rejected: reason=busy, stage={}, ticks={}, moved={}",
                    activeJob.stage, activeJob.ticks, activeJob.moved);
            player.displayClientMessage(Component.translatable("message.storage_consolidator.busy"), true);
            return;
        }
        activeJob = new ConsolidationJob(player, terminal, level, network);
        Storage_consolidator.LOGGER.info("Storage consolidation started: player={}, terminal={}, mode=continuous, yieldMs=3000",
                player.getGameProfile().getName(), terminal.getBlockPos());
        player.displayClientMessage(Component.translatable("message.storage_consolidator.started"), true);
    }

    /** 在服务端 tick 末尾推进任务，预算耗尽只让出回调而不取消任务。 */
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        ConsolidationJob job = activeJob;
        if (job == null) {
            return;
        }
        long start = System.nanoTime();
        try {
            // 玩家离线、终端卸载、终端被移除或网络处理器被重建时释放旧任务。
            TomStorageTerminalAccess access = (TomStorageTerminalAccess) (Object) job.terminal;
            if (event.getServer().getPlayerList().getPlayer(job.player.getUUID()) != job.player
                    || !job.level.isLoaded(job.terminal.getBlockPos())
                    || job.terminal.isRemoved()
                    || job.ticks % 20 == 0 && access.storageConsolidator$getItemHandler() != job.root) {
                job.cancel("validation");
                return;
            }
            TickBudget budget = new TickBudget(start);
            boolean finished;
            try (ConsolidationScope ignored = new ConsolidationScope()) {
                finished = job.process(budget);
            }
            long elapsed = System.nanoTime() - start;
            job.ticks++;
            job.maxTickNanos = Math.max(job.maxTickNanos, elapsed);
            Storage_consolidator.LOGGER.info("Storage consolidation batch: checks={}, transfers={}, elapsedMs={}, finished={}",
                    budget.checks, budget.transfers, elapsed / 1_000_000.0, finished);
            if (System.nanoTime() - job.lastReport >= TimeUnit.SECONDS.toNanos(5)) {
                job.lastReport = System.nanoTime();
                Storage_consolidator.LOGGER.info("Storage consolidation counters: {}", job.diagnosticCounts);
                Storage_consolidator.LOGGER.info("Storage consolidation progress: stage={}, scanned={}, group={}/{}, source={}, candidate={}, moved={}, ticks={}, maxTickUs={}",
                        job.stage, job.slotOrder, job.groupIndex, job.groups.size(), job.sourceIndex,
                        job.candidateIndex, job.moved, job.ticks, TimeUnit.NANOSECONDS.toMicros(job.maxTickNanos));
            }
            if (finished) {
                Storage_consolidator.LOGGER.info("Storage consolidation final counters: {}", job.diagnosticCounts);
                Storage_consolidator.LOGGER.info("Storage consolidation finished: moved={}, slots={}, ticks={}, elapsedMs={}, maxTickUs={}",
                        job.moved, job.slotOrder, job.ticks,
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - job.started),
                        TimeUnit.NANOSECONDS.toMicros(job.maxTickNanos));
                job.player.displayClientMessage(Component.translatable("message.storage_consolidator.completed", job.moved), true);
                activeJob = null;
            } else {
                Storage_consolidator.LOGGER.info("Storage consolidation yielded after callback budget: moved={}, slots={}, elapsedMs={}, continuing=true",
                        job.moved, job.slotOrder, elapsed / 1_000_000.0);
            }
        } catch (RuntimeException exception) {
            activeJob = null;
            Storage_consolidator.LOGGER.error("Storage consolidation cancelled after inventory failure", exception);
            job.player.displayClientMessage(Component.translatable("message.storage_consolidator.cancelled"), true);
        }
    }

    /** 服务端停止时释放任务持有的世界和玩家引用。 */
    public static void onServerStopped(ServerStoppedEvent event) {
        if (activeJob != null) {
            Storage_consolidator.LOGGER.info("Storage consolidation released on server stop: ticks={}, moved={}",
                    activeJob.ticks, activeJob.moved);
        }
        activeJob = null;
    }

    /** 单次回调最多连续运行三秒。 */
    private static final class TickBudget {
        /** 本次回调的截止时间。 */
        private final long deadline;
        /** 本次回调执行的检查数。 */
        private int checks;
        /** 本次回调实际搬运的次数。 */
        private int transfers;

        /** 创建三秒回调预算。 */
        private TickBudget(long start) {
            deadline = start + TimeUnit.SECONDS.toNanos(3);
        }

        /** 检查是否仍可继续执行。 */
        private boolean available() {
            return System.nanoTime() < deadline;
        }
    }

    /** 保存网络库存、游标和诊断状态的可暂停任务。 */
    private static final class ConsolidationJob {
        /** 发起整理的玩家。 */
        private final ServerPlayer player;
        /** 发起整理的终端。 */
        private final StorageTerminalBlockEntity terminal;
        /** 终端所在服务端世界。 */
        private final ServerLevel level;
        /** Tom's Storage 当前合并库存处理器。 */
        private final IItemHandler root;
        /** 任务开始时间。 */
        private final long started = System.nanoTime();
        /** 上次进度日志时间。 */
        private long lastReport = started;
        /** 诊断计数。 */
        private final Map<String, Long> diagnosticCounts = new LinkedHashMap<>();
        /** 网络处理器的单一来源。 */
        private final List<InventorySource> sources = new ArrayList<>();
        /** 物品到同组件分组的索引。 */
        private final Map<Item, List<ItemGroup>> byItem = new IdentityHashMap<>();
        /** 所有待处理物品组。 */
        private final List<ItemGroup> groups = new ArrayList<>();
        /** 整理过程中登记的空槽。 */
        private final TargetIndex emptyIndex = new TargetIndex();
        /** 已知来源方块实体身份。 */
        private final Map<BlockPos, net.minecraft.world.level.block.entity.BlockEntity> sourceEntities = new java.util.HashMap<>();
        /** 已经参与合并的物理槽位。 */
        private final Set<PhysicalSlot> destinations = new java.util.HashSet<>();
        /** 当前阶段：扫描槽位或搬运。 */
        private int stage = 1;
        /** 来源游标。 */
        private int sourceScan;
        /** 来源内槽位游标。 */
        private int slotScan;
        /** 全局槽位序号。 */
        private int slotOrder;
        /** 物品组游标。 */
        private int groupIndex;
        /** 当前组来源游标。 */
        private int sourceIndex;
        /** 当前组候选游标。 */
        private int candidateIndex;
        /** 当前来源槽位。 */
        private SlotRef currentSource;
        /** 当前来源的物品副本。 */
        private ItemStack currentStack;
        /** 当前最佳目标。 */
        private TargetCandidate best;
        /** 是否已经建立当前来源的候选索引。 */
        private boolean indexReady;
        /** 是否正在搜索空槽。 */
        private boolean searchingEmpty;
        /** 当前候选队列。 */
        private final java.util.PriorityQueue<TargetCandidate> rankedTargets = new java.util.PriorityQueue<>(targetComparator());
        /** 暂缓到下一轮恢复的候选。 */
        private final List<TargetCandidate> deferredTargets = new ArrayList<>();
        /** 任务 tick 计数。 */
        private int ticks;
        /** 已搬运数量。 */
        private long moved;
        /** 搬运序号。 */
        private long transferSequence;
        /** 上一次搬运的来源。 */
        private SlotRef previousSource;
        /** 上一次搬运的目标。 */
        private SlotRef previousTarget;
        /** 连续相同搬运对的次数。 */
        private int repeatedPair;
        /** 历史最大单次回调耗时。 */
        private long maxTickNanos;

        /** 创建一个以终端网络为根的任务。 */
        private ConsolidationJob(ServerPlayer player, StorageTerminalBlockEntity terminal, ServerLevel level, IItemHandler root) {
            this.player = player;
            this.terminal = terminal;
            this.level = level;
            this.root = root;
            BlockPos position = terminal.getBlockPos();
            sources.add(new InventorySource(root, position));
            sourceEntities.put(position, level.getBlockEntity(position));
            Storage_consolidator.LOGGER.info("Storage consolidation source discovered: index=0, pos={}, block={}, handler={}, slots={}",
                    position, BuiltInRegistries.BLOCK.getKey(level.getBlockState(position).getBlock()),
                    root.getClass().getName(), root.getSlots());
        }

        /** 逐步执行扫描、分组、候选选择和搬运。 */
        private boolean process(TickBudget budget) {
            while (budget.available()) {
                budget.checks++;
                if (stage == 1) {
                    if (sourceScan >= sources.size()) {
                        stage = 2;
                        continue;
                    }
                    InventorySource source = sources.get(sourceScan);
                    if (slotScan >= source.handler().getSlots()) {
                        sourceScan++;
                        slotScan = 0;
                        continue;
                    }
                    SlotRef slot = new SlotRef(source.handler(), source.position(), slotScan++, slotOrder++);
                    ItemStack stack = readStack(slot);
                    if (stack.isEmpty()) {
                        emptyIndex.registerEmpty(slot);
                        continue;
                    }
                    List<ItemGroup> variants = byItem.computeIfAbsent(stack.getItem(), ignored -> new ArrayList<>());
                    ItemGroup group = null;
                    for (ItemGroup variant : variants) {
                        if (ItemStack.isSameItemSameTags(variant.template(), stack)) {
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
                if (groupIndex >= groups.size()) {
                    return true;
                }
                ItemGroup group = groups.get(groupIndex);
                if (group.members().size() <= 1 || group.template().getMaxStackSize() <= 1
                        || sourceIndex >= group.slots().size()) {
                    resetGroup();
                    continue;
                }
                if (currentSource == null) {
                    currentSource = group.slots().get(sourceIndex);
                    currentStack = readStack(currentSource);
                    candidateIndex = 0;
                    searchingEmpty = false;
                    best = null;
                    indexReady = false;
                    if (destinations.contains(physicalSlot(currentSource)) || currentStack.isEmpty()
                            || !ItemStack.isSameItemSameTags(currentStack, group.template())) {
                        sourceIndex++;
                        currentSource = null;
                    }
                    continue;
                }
                List<SlotRef> candidates = searchingEmpty ? emptyIndex.emptySlots() : group.slots();
                if (!deferredTargets.isEmpty() && !indexReady) {
                    rankedTargets.add(deferredTargets.remove(deferredTargets.size() - 1));
                    continue;
                }
                if (!indexReady && candidateIndex < candidates.size()) {
                    SlotRef candidate = candidates.get(candidateIndex++);
                    TargetCandidate found = addTargetCandidate(group, currentSource, currentStack, candidate);
                    if (found != null) {
                        rankedTargets.add(found);
                    }
                    continue;
                }
                if (!indexReady && rankedTargets.isEmpty() && !searchingEmpty) {
                    searchingEmpty = true;
                    candidateIndex = 0;
                    continue;
                }
                indexReady = true;
                if (best == null && !rankedTargets.isEmpty()) {
                    TargetCandidate candidate = rankedTargets.poll();
                    ItemStack actualSource = readStack(currentSource);
                    ItemStack actualTarget = readStack(candidate.slot());
                    if (actualSource.isEmpty() || !ItemStack.isSameItemSameTags(actualSource, group.template())) {
                        sourceIndex++;
                        currentSource = null;
                        rankedTargets.add(candidate);
                        continue;
                    }
                    if (sameSlot(currentSource, candidate.slot())) {
                        deferredTargets.add(candidate);
                        continue;
                    }
                    if (!actualTarget.isEmpty() && !ItemStack.isSameItemSameTags(actualTarget, group.template())
                            || actualTarget.getCount() >= effectiveStackLimit(candidate.slot(), group.template())) {
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
                    ItemStack actual = readStack(currentSource);
                    ItemStack target = readStack(best.slot());
                    if (ItemStack.isSameItemSameTags(actual, group.template())
                            && (target.isEmpty() || ItemStack.isSameItemSameTags(actual, target))) {
                        budget.transfers++;
                        int count = transfer(currentSource, best.slot(), actual);
                        moved += count;
                        if (count > 0 && readStack(best.slot()).getCount() < effectiveStackLimit(best.slot(), group.template())) {
                            rankedTargets.add(new TargetCandidate(best.slot(), true,
                                    effectiveStackLimit(best.slot(), group.template()),
                                    readStack(best.slot()).getCount(),
                                    Math.max(0, effectiveStackLimit(best.slot(), group.template()) - readStack(best.slot()).getCount())));
                        }
                        if (!readStack(currentSource).isEmpty()) {
                            currentSource = null;
                            continue;
                        }
                    }
                }
                emptyIndex.registerEmptyIfEmpty(currentSource);
                sourceIndex++;
                currentSource = null;
                best = null;
                rankedTargets.clear();
                deferredTargets.clear();
                indexReady = false;
            }
            return false;
        }

        /** 清理当前物品组并切换到下一个物品组。 */
        private void resetGroup() {
            groupIndex++;
            sourceIndex = 0;
            currentSource = null;
            best = null;
            rankedTargets.clear();
            deferredTargets.clear();
            indexReady = false;
            searchingEmpty = false;
            destinations.clear();
        }

        /** 记录取消原因并释放当前任务。 */
        private void cancel(String reason) {
            Storage_consolidator.LOGGER.info("Storage consolidation cancelled by validation: reason={}, stage={}, ticks={}, moved={}",
                    reason, stage, ticks, moved);
            player.displayClientMessage(Component.translatable("message.storage_consolidator.cancelled"), true);
            activeJob = null;
        }
    }

    /** 检查目标槽位并生成带容量评分的候选。 */
    private static TargetCandidate addTargetCandidate(ItemGroup group, SlotRef source, ItemStack sourceStack, SlotRef candidate) {
        if (sameSlot(source, candidate)) {
            return null;
        }
        ItemStack target = readStack(candidate);
        boolean sameItem = !target.isEmpty() && ItemStack.isSameItemSameTags(group.template(), target);
        if (!target.isEmpty() && !sameItem) {
            return null;
        }
        int limit = effectiveStackLimit(candidate, group.template());
        if (limit <= 0 || target.getCount() >= limit) {
            return null;
        }
        if (target.isEmpty() && group.members().contains(physicalSlot(candidate))) {
            return null;
        }
        if (target.isEmpty() && !isValidTarget(candidate, group.template())) {
            return null;
        }
        int accepted = previewInsert(candidate, sourceStack);
        if (accepted <= 0) {
            return null;
        }
        int count = target.isEmpty() ? 0 : target.getCount();
        return new TargetCandidate(candidate, sameItem, limit, count, Math.max(0, limit - count));
    }

    /** 按相同物品、容量、已有数量和剩余空间选择目标。 */
    private static Comparator<TargetCandidate> targetComparator() {
        return Comparator.comparingInt((TargetCandidate target) -> target.sameItem() ? 1 : 0).reversed()
                .thenComparing(Comparator.comparingInt(TargetCandidate::limit).reversed())
                .thenComparing(Comparator.comparingInt(TargetCandidate::count).reversed())
                .thenComparing(Comparator.comparingInt(TargetCandidate::free).reversed())
                .thenComparingInt(target -> target.slot().order());
    }

    /** 检查空槽是否接受目标物品。 */
    private static boolean isValidTarget(SlotRef target, ItemStack template) {
        try {
            return target.handler().isItemValid(target.index(), template);
        } catch (RuntimeException exception) {
            Storage_consolidator.LOGGER.debug("Skipped a target slot because its validity check failed", exception);
            return false;
        }
    }

    /** 使用模拟插入计算目标槽位本次可接收数量。 */
    private static int previewInsert(SlotRef target, ItemStack sourceStack) {
        int limit = effectiveStackLimit(target, sourceStack);
        if (limit <= 0) {
            return 0;
        }
        int requested = Math.min(sourceStack.getCount(), limit);
        try {
            ItemStack remainder = target.handler().insertItem(target.index(), sourceStack.copyWithCount(requested), true);
            return requested - remainder.getCount();
        } catch (RuntimeException exception) {
            Storage_consolidator.LOGGER.debug("Skipped a target slot because its simulated insertion failed", exception);
            return 0;
        }
    }

    /** 使用处理器实际槽位上限，保留 Sophisticated 的堆叠升级容量。 */
    private static int effectiveStackLimit(SlotRef target, ItemStack template) {
        try {
            return Math.min(target.handler().getSlotLimit(target.index()), template.getMaxStackSize());
        } catch (RuntimeException exception) {
            Storage_consolidator.LOGGER.debug("Could not read a target slot limit", exception);
            return 0;
        }
    }

    /** 先模拟抽取和插入，再提交变化；部分失败的剩余物退回玩家。 */
    private static int transfer(SlotRef source, SlotRef target, ItemStack sourceStack) {
        if (sameSlot(source, target) || !isLive(source) || !isLive(target)) {
            return 0;
        }
        ItemStack sourceBefore = readStack(source);
        ItemStack targetBefore = readStack(target);
        int possible = previewInsert(target, sourceStack);
        if (possible <= 0) {
            return 0;
        }
        ItemStack extractedPreview;
        try {
            extractedPreview = source.handler().extractItem(source.index(), possible, true);
        } catch (RuntimeException exception) {
            Storage_consolidator.LOGGER.debug("Skipped a source slot because its simulated extraction failed", exception);
            return 0;
        }
        if (extractedPreview.isEmpty()) {
            return 0;
        }
        ItemStack extracted;
        try {
            extracted = source.handler().extractItem(source.index(), extractedPreview.getCount(), false);
        } catch (RuntimeException exception) {
            Storage_consolidator.LOGGER.debug("Skipped a source slot because its extraction failed", exception);
            return 0;
        }
        if (extracted.isEmpty()) {
            return 0;
        }
        ItemStack remainder;
        try {
            remainder = target.handler().insertItem(target.index(), extracted.copy(), false);
        } catch (RuntimeException exception) {
            Storage_consolidator.LOGGER.error("Target insertion failed after extraction; returning items to player", exception);
            return 0;
        }
        int inserted = extracted.getCount() - remainder.getCount();
        if (!remainder.isEmpty()) {
            Storage_consolidator.LOGGER.error("A target accepted only part of a simulated transfer; the remainder was not discarded");
            return 0;
        }
        ItemStack sourceAfter = readStack(source);
        ItemStack targetAfter = readStack(target);
        boolean mismatch = sourceBefore.getCount() - sourceAfter.getCount() != extracted.getCount()
                || targetAfter.getCount() - targetBefore.getCount() != inserted;
        if (mismatch) {
            throw new IllegalStateException("Inventory transfer delta mismatch; consolidation stopped");
        }
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
            if (reversed) {
                diagnostic("transfer_reverse_pair", source, target);
            }
            if (job.repeatedPair > 1) {
                diagnostic("transfer_repeated_pair", source, target);
            }
            if (sequence <= 64 || sequence % 1024 == 0) {
                Storage_consolidator.LOGGER.info("Storage consolidation transfer: seq={}, tick={}, src={}, dst={}, item={}, moved={}",
                        sequence, job.ticks, describeSlot(source), describeSlot(target),
                        BuiltInRegistries.ITEM.getKey(sourceBefore.getItem()), inserted);
            }
        }
        return Math.max(0, inserted);
    }

    /** 累计诊断事件，并限制详细日志数量。 */
    private static void diagnostic(String reason, SlotRef source, SlotRef target) {
        ConsolidationJob job = activeJob;
        if (job == null) {
            return;
        }
        long count = job.diagnosticCounts.merge(reason, 1L, Long::sum);
        if (count <= 4 || (count & (count - 1)) == 0) {
            Storage_consolidator.LOGGER.info("Storage consolidation event: reason={}, count={}, tick={}, src={}, dst={}",
                    reason, count, job.ticks, describeSlot(source), describeSlot(target));
        }
    }

    /** 只检查任务开始时记录的终端实体是否仍在原位置。 */
    private static boolean isLive(SlotRef slot) {
        ConsolidationJob job = activeJob;
        return job == null || slot.position() == null
                || job.level.isLoaded(slot.position())
                && job.sourceEntities.get(slot.position()) == job.level.getBlockEntity(slot.position());
    }

    /** 根网络处理器的槽位身份由处理器引用和索引共同决定。 */
    private static PhysicalSlot physicalSlot(SlotRef slot) {
        return new PhysicalSlot(slot.handler(), slot.index());
    }

    /** 判断两个槽位是否为同一物理槽位。 */
    private static boolean sameSlot(SlotRef first, SlotRef second) {
        return physicalSlot(first).equals(physicalSlot(second));
    }

    /** 安全读取槽位副本，异常时按空槽处理。 */
    private static ItemStack readStack(SlotRef slot) {
        if (!isLive(slot)) {
            return ItemStack.EMPTY;
        }
        try {
            ItemStack stack = slot.handler().getStackInSlot(slot.index());
            return stack == null ? ItemStack.EMPTY : stack.copy();
        } catch (RuntimeException exception) {
            diagnostic("slot_read_exception", slot, null);
            Storage_consolidator.LOGGER.debug("Could not read an inventory slot", exception);
            return ItemStack.EMPTY;
        }
    }

    /** 输出槽位诊断身份。 */
    private static String describeSlot(SlotRef slot) {
        if (slot == null) {
            return "none";
        }
        return "pos=" + slot.position() + "/slot=" + slot.index() + "/order=" + slot.order()
                + "/handler=" + slot.handler().getClass().getName() + "@" + System.identityHashCode(slot.handler());
    }

    /** 空槽索引，避免同一槽位被重复登记。 */
    private static final class TargetIndex {
        /** 空槽列表。 */
        private final List<SlotRef> emptySlots = new ArrayList<>();
        /** 已登记空槽的身份集合。 */
        private final Set<PhysicalSlot> knownEmptySlots = new java.util.HashSet<>();

        /** 返回当前空槽列表。 */
        private List<SlotRef> emptySlots() {
            return emptySlots;
        }

        /** 登记一个空槽。 */
        private void registerEmpty(SlotRef slot) {
            if (knownEmptySlots.add(physicalSlot(slot))) {
                emptySlots.add(slot);
            }
        }

        /** 仅在槽位实际变空时登记。 */
        private void registerEmptyIfEmpty(SlotRef slot) {
            if (slot != null && readStack(slot).isEmpty()) {
                registerEmpty(slot);
            }
        }
    }

    /** 网络中的一个逻辑库存来源。 */
    private record InventorySource(IItemHandler handler, BlockPos position) {
    }

    /** 一个处理器槽位及其扫描序号。 */
    private record SlotRef(IItemHandler handler, BlockPos position, int index, int order) {
    }

    /** 带排序数据的目标槽位。 */
    private record TargetCandidate(SlotRef slot, boolean sameItem, int limit, int count, int free) {
    }

    /** 同一物品和标签的来源分组。 */
    private record ItemGroup(ItemStack template, List<SlotRef> slots, Set<PhysicalSlot> members) {
        /** 创建一个空的物品分组。 */
        private ItemGroup(ItemStack template) {
            this(template, new ArrayList<>(), new java.util.HashSet<>());
        }
    }

    /** 通过引用身份区分不同处理器的槽位。 */
    private record PhysicalSlot(Object inventory, int index) {
        /** 只按处理器引用比较，避免自定义 equals 合并不同库存。 */
        @Override
        public boolean equals(Object other) {
            return other instanceof PhysicalSlot slot && inventory == slot.inventory && index == slot.index;
        }

        /** 与引用身份比较一致的哈希值。 */
        @Override
        public int hashCode() {
            return 31 * System.identityHashCode(inventory) + index;
        }
    }
}
