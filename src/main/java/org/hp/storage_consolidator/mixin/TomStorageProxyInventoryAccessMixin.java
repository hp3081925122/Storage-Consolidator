package org.hp.storage_consolidator.mixin;

import com.tom.storagemod.inventory.IInventoryAccess;
import com.tom.storagemod.inventory.PlatformProxyInventoryAccess;
import org.hp.storage_consolidator.access.TomStorageProxyAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * 暴露 Tom's Storage 代理库存访问器的底层来源，用于追踪真实方块位置。
 */
@Mixin(value = PlatformProxyInventoryAccess.class, remap = false)
public abstract class TomStorageProxyInventoryAccessMixin implements TomStorageProxyAccess {
    @Shadow
    private IInventoryAccess access;

    /**
     * 返回代理库存访问器包裹的底层访问器。
     */
    @Override
    public IInventoryAccess storageConsolidator$getWrappedAccess() {
        return access;
    }
}
