package org.hp.storage_consolidator.access;

import net.neoforged.neoforge.items.IItemHandler;

/** 仅用于解析过滤包装的物理身份与容量。 */
public interface FilteredInventoryAccess {
    IItemHandler storageConsolidator$getInventory();
}
