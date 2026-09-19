package org.hp.storage_consolidator.mixin;

import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import net.p3pp3rf1y.sophisticatedcore.api.IStorageWrapper;
import net.p3pp3rf1y.sophisticatedcore.inventory.InventoryHandler;
import net.p3pp3rf1y.sophisticatedcore.inventory.IInventoryPartHandler;
import net.p3pp3rf1y.sophisticatedcore.settings.nosort.NoSortSettingsCategory;
import org.hp.storage_consolidator.access.SophisticatedInventoryAccess;
import org.hp.storage_consolidator.compat.ConsolidationScope;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;

/** 整理期间固定目标槽位，并抑制会消耗、变换或重分配物品的升级回调。 */
@Pseudo
@Mixin(targets = "net.p3pp3rf1y.sophisticatedcore.inventory.InventoryHandler", remap = false)
public abstract class SophisticatedInventoryMixin implements SophisticatedInventoryAccess {
    @Shadow @Final protected IStorageWrapper storageWrapper;
    @Shadow private java.util.function.BooleanSupplier shouldInsertIntoEmpty;

    /** 分区和用户的禁止整理设置均由上游负责判定。 */
    @Override
    public boolean storageConsolidator$canSort(int slot) {
        InventoryHandler handler = (InventoryHandler) (Object) this;
        return handler.isSlotAccessible(slot) && !handler.isInfinite(slot)
                && handler.getInventoryPartitioner().getPartBySlot(slot) instanceof IInventoryPartHandler.Default
                && !handler.getNoSortSlots().contains(slot)
                && !storageWrapper.getSettingsHandler().getTypeCategory(NoSortSettingsCategory.class).isSlotSelected(slot);
    }

    /** 保留堆叠升级的真实容量。 */
    @Override
    public int storageConsolidator$stackLimit(int slot, ItemStack stack) {
        long capacity = ((InventoryHandler) (Object) this)
                .getCapacityAsLong(slot, ItemResource.of(stack));
        return (int) Math.min(Integer.MAX_VALUE, capacity);
    }

    /** 外层 Tom 过滤器仍会先执行，只替换上游重分配槽位的最后一步。 */
    @Inject(method = "insert(ILnet/neoforged/neoforge/transfer/item/ItemResource;ILnet/neoforged/neoforge/transfer/transaction/TransactionContext;)I", at = @At("HEAD"), cancellable = true)
    private void storageConsolidator$insert(int slot, ItemResource resource, int amount, TransactionContext transaction, CallbackInfoReturnable<Integer> cir) {
        if (!ConsolidationScope.active()) return;
        InventoryHandler handler = (InventoryHandler) (Object) this;
        if (!storageConsolidator$canSort(slot) || !handler.isValid(slot, resource)
                || handler.getResource(slot).isEmpty() && !shouldInsertIntoEmpty.getAsBoolean()) {
            cir.setReturnValue(0);
        }
    }

    /** 抽取同样尊重禁止整理与特殊分区，不从无限槽位制造物品。 */
    @Inject(method = "extract(ILnet/neoforged/neoforge/transfer/item/ItemResource;ILnet/neoforged/neoforge/transfer/transaction/TransactionContext;)I", at = @At("HEAD"), cancellable = true)
    private void storageConsolidator$extract(int slot, ItemResource resource, int amount, TransactionContext transaction, CallbackInfoReturnable<Integer> cir) {
        if (ConsolidationScope.active() && !storageConsolidator$canSort(slot)) cir.setReturnValue(0);
    }

    /** 整理仅搬动物品，不触发 void 或自动压缩等插入响应。 */
    @Inject(method = "runOnBeforeInsert(Lnet/neoforged/neoforge/transfer/item/ItemResource;ILnet/p3pp3rf1y/sophisticatedcore/api/IStorageWrapper;)I", at = @At("HEAD"), cancellable = true)
    private void storageConsolidator$beforeInsert(ItemResource resource, int amount, IStorageWrapper wrapper, CallbackInfoReturnable<Integer> cir) {
        if (ConsolidationScope.active()) cir.setReturnValue(0);
    }

    /** 槽位级插入入口也不允许升级吞掉整理中的剩余物。 */
    @Inject(method = "runOnBeforeInsert(ILnet/neoforged/neoforge/transfer/item/ItemResource;ILnet/p3pp3rf1y/sophisticatedcore/api/IStorageWrapper;)I", at = @At("HEAD"), cancellable = true)
    private void storageConsolidator$beforeInsertSlot(int slot, ItemResource resource, int amount, IStorageWrapper wrapper, CallbackInfoReturnable<Integer> cir) {
        if (ConsolidationScope.active()) cir.setReturnValue(0);
    }

    /** 满槽剩余物必须返回，不交给溢出销毁升级。 */
    @Inject(method = "handleOverflow(Lnet/neoforged/neoforge/transfer/item/ItemResource;I)I", at = @At("HEAD"), cancellable = true)
    private void storageConsolidator$handleOverflow(ItemResource resource, int amount, CallbackInfoReturnable<Integer> cir) {
        if (ConsolidationScope.active()) cir.setReturnValue(0);
    }

    /** 存储级溢出升级在整理期间不应消费剩余物。 */
    @Inject(method = "triggerStorageOverflowUpgrades(Lnet/neoforged/neoforge/transfer/item/ItemResource;I)I", at = @At("HEAD"), cancellable = true)
    private void storageConsolidator$storageOverflow(ItemResource resource, int amount, CallbackInfoReturnable<Integer> cir) {
        if (ConsolidationScope.active()) cir.setReturnValue(0);
    }

    /** 槽位级溢出升级在整理期间不应消费剩余物。 */
    @Inject(method = "triggerSlotOverflowUpgrades(Lnet/neoforged/neoforge/transfer/item/ItemResource;I)I", at = @At("HEAD"), cancellable = true)
    private void storageConsolidator$slotOverflow(ItemResource resource, int amount, CallbackInfoReturnable<Integer> cir) {
        if (ConsolidationScope.active()) cir.setReturnValue(0);
    }

    /** 正常存储更新与保存仍执行，仅抑制升级响应。 */
    @Inject(method = "runOnAfterInsert(ILnet/neoforged/neoforge/transfer/transaction/TransactionContext;)V", at = @At("HEAD"), cancellable = true)
    private void storageConsolidator$afterInsert(int slot, TransactionContext transaction, CallbackInfo ci) {
        if (ConsolidationScope.active()) ci.cancel();
    }

    /** 避免抽取后升级又回填或变换刚刚读取的槽位。 */
    @Inject(method = "runOnAfterExtract(ILnet/neoforged/neoforge/transfer/item/ItemResource;)V", at = @At("HEAD"), cancellable = true)
    private void storageConsolidator$afterExtract(int slot, ItemResource resource, CallbackInfo ci) {
        if (ConsolidationScope.active()) ci.cancel();
    }
}
