package org.hp.storage_consolidator.mixin;

import com.tom.storagemod.tile.StorageTerminalBlockEntity;
import net.minecraftforge.items.IItemHandler;
import org.hp.storage_consolidator.access.TomStorageTerminalAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** 暴露 Tom's Storage 1.20.1 终端内部维护的合并库存处理器。 */
@Mixin(value = StorageTerminalBlockEntity.class, remap = false)
public abstract class TomStorageTerminalBlockEntityMixin implements TomStorageTerminalAccess {
    /** Tom's Storage 在 1.20.1 Forge 中直接缓存的网络处理器。 */
    @Shadow
    private IItemHandler itemHandler;

    /** 返回当前终端的网络库存。 */
    @Override
    public IItemHandler storageConsolidator$getItemHandler() {
        return itemHandler;
    }
}
