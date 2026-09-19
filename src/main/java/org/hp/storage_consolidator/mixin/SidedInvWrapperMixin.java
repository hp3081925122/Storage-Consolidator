package org.hp.storage_consolidator.mixin;

import net.minecraft.core.Direction;
import net.minecraft.world.WorldlyContainer;
import net.neoforged.neoforge.transfer.item.WorldlyContainerWrapper;
import org.hp.storage_consolidator.access.SidedInventoryAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 按当前 NeoForge 字段暴露方向包装身份，不修改插入或抽取规则。
 */
@Mixin(value = WorldlyContainerWrapper.class, remap = false)
public interface SidedInvWrapperMixin extends SidedInventoryAccess {
    /** 返回包装持有的真实库存。 */
    @Override
    @Accessor("container")
    WorldlyContainer storageConsolidator$getContainer();

    /** 返回包装使用的访问方向。 */
    @Override
    @Accessor("side")
    Direction storageConsolidator$getSide();
}
