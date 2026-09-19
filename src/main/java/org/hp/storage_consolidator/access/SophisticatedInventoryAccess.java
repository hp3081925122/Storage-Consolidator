package org.hp.storage_consolidator.access;

import net.minecraft.world.item.ItemStack;

/** 不引用可选模组类型的库存适配契约。 */
public interface SophisticatedInventoryAccess {
    /** 避开无限库存、压缩分区、不可访问和禁止整理槽位。 */
    boolean storageConsolidator$canSort(int slot);
    /** 使用上游计算的实际堆叠容量。 */
    int storageConsolidator$stackLimit(int slot, ItemStack stack);
}
