package org.hp.storage_consolidator.access;

import com.tom.storagemod.inventory.IInventoryAccess;

/**
 * 向整理服务暴露 Tom's Storage 终端的真实网络访问器。
 */
public interface TomStorageTerminalAccess {
    IInventoryAccess storageConsolidator$getInventoryAccess();
}
