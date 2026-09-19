package org.hp.storage_consolidator.mixin;

import net.minecraft.world.item.ItemStack;
import net.p3pp3rf1y.sophisticatedcore.api.IStorageWrapper;
import net.p3pp3rf1y.sophisticatedcore.inventory.InventoryHandler;
import net.p3pp3rf1y.sophisticatedcore.inventory.IInventoryPartHandler;
import net.p3pp3rf1y.sophisticatedcore.inventory.IItemHandlerSimpleInserter;
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
        return ((InventoryHandler) (Object) this).getStackLimit(slot, stack);
    }

    /** 外层 Tom 过滤器仍会先执行，只替换上游重分配槽位的最后一步。 */
    @Inject(method = "insertItem(ILnet/minecraft/world/item/ItemStack;Z)Lnet/minecraft/world/item/ItemStack;", at = @At("HEAD"), cancellable = true)
    private void storageConsolidator$insert(int slot, ItemStack stack, boolean simulate, CallbackInfoReturnable<ItemStack> cir) {
        if (!ConsolidationScope.active()) return;
        InventoryHandler handler = (InventoryHandler) (Object) this;
        if (!storageConsolidator$canSort(slot) || !handler.isItemValid(slot, stack)
                || handler.getStackInSlot(slot).isEmpty() && !shouldInsertIntoEmpty.getAsBoolean()) {
            cir.setReturnValue(stack);
            return;
        }
        cir.setReturnValue(handler.insertItemOnlyToSlot(slot, stack, simulate));
    }

    /** 抽取同样尊重禁止整理与特殊分区，不从无限槽位制造物品。 */
    @Inject(method = "extractItem(IIZ)Lnet/minecraft/world/item/ItemStack;", at = @At("HEAD"), cancellable = true)
    private void storageConsolidator$extract(int slot, int amount, boolean simulate, CallbackInfoReturnable<ItemStack> cir) {
        if (ConsolidationScope.active() && !storageConsolidator$canSort(slot)) cir.setReturnValue(ItemStack.EMPTY);
    }

    /** 整理仅搬动物品，不触发 void 或自动压缩等插入响应。 */
    @Inject(method = "runOnBeforeInsert(ILnet/minecraft/world/item/ItemStack;ZLnet/p3pp3rf1y/sophisticatedcore/inventory/InventoryHandler;Lnet/p3pp3rf1y/sophisticatedcore/api/IStorageWrapper;)Lnet/minecraft/world/item/ItemStack;", at = @At("HEAD"), cancellable = true)
    private void storageConsolidator$beforeInsert(int slot, ItemStack stack, boolean simulate, InventoryHandler handler, IStorageWrapper wrapper, CallbackInfoReturnable<ItemStack> cir) {
        if (ConsolidationScope.active()) cir.setReturnValue(stack);
    }

    /** 满槽剩余物必须返回，不交给溢出销毁升级。 */
    @Inject(method = {"triggerSlotOverflowUpgrades", "triggerStorageOverflowUpgrades"}, at = @At("HEAD"), cancellable = true)
    private void storageConsolidator$overflow(ItemStack stack, CallbackInfoReturnable<ItemStack> cir) {
        if (ConsolidationScope.active()) cir.setReturnValue(stack);
    }

    /** 正常存储更新与保存仍执行，仅抑制升级响应。 */
    @Inject(method = "runOnAfterInsert", at = @At("HEAD"), cancellable = true)
    private void storageConsolidator$afterInsert(int slot, boolean simulate, IItemHandlerSimpleInserter handler, IStorageWrapper wrapper, CallbackInfo ci) {
        if (ConsolidationScope.active()) ci.cancel();
    }

    /** 避免抽取后升级又回填或变换刚刚读取的槽位。 */
    @Inject(method = "runOnAfterExtract", at = @At("HEAD"), cancellable = true)
    private void storageConsolidator$afterExtract(int slot, IItemHandlerSimpleInserter handler, ItemStack original, CallbackInfo ci) {
        if (ConsolidationScope.active()) ci.cancel();
    }
}
