package org.hp.storage_consolidator.mixin;

import net.neoforged.neoforge.items.IItemHandler;
import org.hp.storage_consolidator.access.FilteredInventoryAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 解包仅用于身份检查，实际搬运仍通过原过滤包装。 */
@Pseudo
@Mixin(targets = "net.p3pp3rf1y.sophisticatedcore.inventory.FilteredItemHandler", remap = false)
public interface SophisticatedFilteredInventoryMixin extends FilteredInventoryAccess {
    /** 获取保留相同槽位索引的底层库存。 */
    @Override
    @Accessor("inventoryHandler")
    IItemHandler storageConsolidator$getInventory();
}
