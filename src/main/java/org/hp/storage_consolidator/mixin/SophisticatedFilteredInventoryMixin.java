package org.hp.storage_consolidator.mixin;

import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.item.ItemResource;
import org.hp.storage_consolidator.access.FilteredInventoryAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 解包仅用于身份检查，实际搬运仍通过原过滤包装。 */
@Pseudo
@Mixin(targets = "net.p3pp3rf1y.sophisticatedcore.inventory.FilteredItemHandler", remap = false)
public interface SophisticatedFilteredInventoryMixin extends FilteredInventoryAccess {
    /** 获取过滤处理器持有的底层库存。 */
    @Override
    @Accessor("inventoryHandler")
    ResourceHandler<ItemResource> storageConsolidator$getInventoryHandler();
}
