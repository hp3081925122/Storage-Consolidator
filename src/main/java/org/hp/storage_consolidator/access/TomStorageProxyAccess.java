package org.hp.storage_consolidator.access;

import com.tom.storagemod.inventory.IInventoryAccess;

/**
 * 向兼容性筛选器暴露 Tom's Storage 代理访问器包裹的底层访问器。
 */
public interface TomStorageProxyAccess {
    IInventoryAccess storageConsolidator$getWrappedAccess();
}
