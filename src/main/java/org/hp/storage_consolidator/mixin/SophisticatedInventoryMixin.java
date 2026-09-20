package org.hp.storage_consolidator.mixin;

import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.IItemHandler;
import net.p3pp3rf1y.sophisticatedcore.api.IStorageWrapper;
import net.p3pp3rf1y.sophisticatedcore.inventory.IInventoryPartHandler;
import net.p3pp3rf1y.sophisticatedcore.inventory.IItemHandlerSimpleInserter;
import net.p3pp3rf1y.sophisticatedcore.inventory.InventoryHandler;
import net.p3pp3rf1y.sophisticatedcore.settings.nosort.NoSortSettingsCategory;
import org.hp.storage_consolidator.access.SophisticatedInventoryAccess;
import org.hp.storage_consolidator.compat.ConsolidationScope;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 整理期间固定 Sophisticated 目标槽位，并抑制会改变物品语义的升级回调。 */
@Pseudo
@Mixin(targets = "net.p3pp3rf1y.sophisticatedcore.inventory.InventoryHandler", remap = false)
public abstract class SophisticatedInventoryMixin implements SophisticatedInventoryAccess {
    /** 读取上游存储设置。 */
    @Shadow @Final protected IStorageWrapper storageWrapper;
    /** 读取空槽写入策略。 */
    @Shadow private java.util.function.BooleanSupplier shouldInsertIntoEmpty;

    /** 只允许普通、可访问、未标记禁止整理的槽位。 */
    @Override
    public boolean storageConsolidator$canSort(int slot) {
        InventoryHandler handler = (InventoryHandler) (Object) this;
        return handler.isSlotAccessible(slot) && !handler.isInfinite(slot)
                && handler.getInventoryPartitioner().getPartBySlot(slot) instanceof IInventoryPartHandler.Default
                && !handler.getNoSortSlots().contains(slot)
                && !storageWrapper.getSettingsHandler().getTypeCategory(NoSortSettingsCategory.class).isSlotSelected(slot);
    }

    /** 保留堆叠升级后的真实容量。 */
    @Override
    public int storageConsolidator$stackLimit(int slot, ItemStack stack) {
        return ((InventoryHandler) (Object) this).getStackLimit(slot, stack);
    }

    /** 整理时把插入限制固定到当前槽位，避免上游重新分配目标。 */
    @Inject(method = "insertItem(ILnet/minecraft/world/item/ItemStack;Z)Lnet/minecraft/world/item/ItemStack;", at = @At("HEAD"), cancellable = true)
    private void storageConsolidator$insert(int slot, ItemStack stack, boolean simulate, CallbackInfoReturnable<ItemStack> callback) {
        if (!ConsolidationScope.active()) {
            return;
        }
        InventoryHandler handler = (InventoryHandler) (Object) this;
        if (!storageConsolidator$canSort(slot) || !handler.isItemValid(slot, stack)
                || handler.getStackInSlot(slot).isEmpty() && !shouldInsertIntoEmpty.getAsBoolean()) {
            callback.setReturnValue(stack);
            return;
        }
        callback.setReturnValue(handler.insertItemOnlyToSlot(slot, stack, simulate));
    }

    /** 整理时禁止从特殊槽位抽取。 */
    @Inject(method = "extractItem(IIZ)Lnet/minecraft/world/item/ItemStack;", at = @At("HEAD"), cancellable = true)
    private void storageConsolidator$extract(int slot, int amount, boolean simulate, CallbackInfoReturnable<ItemStack> callback) {
        if (ConsolidationScope.active() && !storageConsolidator$canSort(slot)) {
            callback.setReturnValue(ItemStack.EMPTY);
        }
    }

    /** 整理只搬动物品，不触发 void、压缩等二次升级。 */
    @Inject(method = "runOnBeforeInsert(ILnet/minecraft/world/item/ItemStack;ZLnet/p3pp3rf1y/sophisticatedcore/inventory/InventoryHandler;Lnet/p3pp3rf1y/sophisticatedcore/api/IStorageWrapper;)Lnet/minecraft/world/item/ItemStack;", at = @At("HEAD"), cancellable = true)
    private void storageConsolidator$beforeInsert(int slot, ItemStack stack, boolean simulate, InventoryHandler handler, IStorageWrapper wrapper, CallbackInfoReturnable<ItemStack> callback) {
        if (ConsolidationScope.active()) {
            callback.setReturnValue(stack);
        }
    }

    /** 满槽剩余物必须原样返回。 */
    @Inject(method = {"triggerSlotOverflowUpgrades", "triggerStorageOverflowUpgrades"}, at = @At("HEAD"), cancellable = true)
    private void storageConsolidator$overflow(ItemStack stack, CallbackInfoReturnable<ItemStack> callback) {
        if (ConsolidationScope.active()) {
            callback.setReturnValue(stack);
        }
    }

    /** 保留正常存储更新，但抑制整理期间的升级响应。 */
    @Inject(method = "runOnAfterInsert", at = @At("HEAD"), cancellable = true)
    private void storageConsolidator$afterInsert(int slot, boolean simulate, IItemHandlerSimpleInserter handler, IStorageWrapper wrapper, CallbackInfo callback) {
        if (ConsolidationScope.active()) {
            callback.cancel();
        }
    }

    /** 避免抽取后升级回填或变换刚刚读取的槽位。 */
    @Inject(method = "runOnAfterExtract", at = @At("HEAD"), cancellable = true)
    private void storageConsolidator$afterExtract(int slot, IItemHandlerSimpleInserter handler, ItemStack original, CallbackInfo callback) {
        if (ConsolidationScope.active()) {
            callback.cancel();
        }
    }
}
