package org.hp.storage_consolidator.access;

import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.item.ItemResource;

/** 仅用于解析过滤包装的物理身份与容量。 */
public interface FilteredInventoryAccess {
    ResourceHandler<ItemResource> storageConsolidator$getInventoryHandler();
}
