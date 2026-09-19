package org.hp.storage_consolidator.mixin;

import com.tom.storagemod.block.entity.StorageTerminalBlockEntity;
import com.tom.storagemod.inventory.IInventoryAccess;
import com.tom.storagemod.inventory.NetworkInventory;
import net.minecraft.world.level.Level;
import org.hp.storage_consolidator.access.TomStorageTerminalAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * 暴露 Tom's Storage 终端已经解析好的网络访问器，避免重复扫描网络。
 */
@Mixin(value = StorageTerminalBlockEntity.class, remap = false)
public abstract class TomStorageTerminalBlockEntityMixin implements TomStorageTerminalAccess {
    @Shadow
    private NetworkInventory itemCache;

    /**
     * 返回当前终端的服务端网络访问器。
     */
    @Override
    public IInventoryAccess storageConsolidator$getInventoryAccess() {
        StorageTerminalBlockEntity terminal = (StorageTerminalBlockEntity) (Object) this;
        Level level = terminal.getLevel();
        if (level == null || level.isClientSide) {
            return null;
        }
        return itemCache.getAccess(level, terminal.getBlockPos());
    }
}
