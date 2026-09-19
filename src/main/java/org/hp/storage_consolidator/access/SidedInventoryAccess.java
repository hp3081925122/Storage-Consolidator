package org.hp.storage_consolidator.access;

import net.minecraft.core.Direction;
import net.minecraft.world.WorldlyContainer;

/**
 * 暴露方向包装的底层库存及方向，仅用于计算物理槽位身份。
 */
public interface SidedInventoryAccess {
    WorldlyContainer storageConsolidator$getContainer();
    Direction storageConsolidator$getSide();
}
