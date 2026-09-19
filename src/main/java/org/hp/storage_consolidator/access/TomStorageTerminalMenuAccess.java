package org.hp.storage_consolidator.access;

import com.tom.storagemod.block.entity.StorageTerminalBlockEntity;

/**
 * 向网络负载暴露当前打开的 Tom's Storage 终端方块实体。
 */
public interface TomStorageTerminalMenuAccess {
    StorageTerminalBlockEntity storageConsolidator$getTerminal();
}
