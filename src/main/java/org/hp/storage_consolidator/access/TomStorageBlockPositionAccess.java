package org.hp.storage_consolidator.access;

import net.minecraft.core.BlockPos;

/**
 * 向兼容性筛选器暴露 Tom's Storage 库存访问器对应的方块位置。
 */
public interface TomStorageBlockPositionAccess {
    BlockPos storageConsolidator$getPosition();
}
