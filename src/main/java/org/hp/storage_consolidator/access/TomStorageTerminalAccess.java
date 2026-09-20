package org.hp.storage_consolidator.access;

import net.minecraftforge.items.IItemHandler;

/** 向整理服务暴露 Tom's Storage 1.20.1 Forge 终端的合并库存处理器。 */
public interface TomStorageTerminalAccess {
    /** 返回终端当前维护的网络库存处理器。 */
    IItemHandler storageConsolidator$getItemHandler();
}
