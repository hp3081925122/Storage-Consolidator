package org.hp.storage_consolidator.mixin;

import com.tom.storagemod.block.entity.StorageTerminalBlockEntity;
import com.tom.storagemod.menu.StorageTerminalMenu;
import org.hp.storage_consolidator.access.TomStorageTerminalMenuAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * 暴露当前菜单绑定的 Tom's Storage 终端，供服务端重新验证操作来源。
 */
@Mixin(value = StorageTerminalMenu.class, remap = false)
public abstract class TomStorageTerminalMenuMixin implements TomStorageTerminalMenuAccess {
    @Shadow
    protected StorageTerminalBlockEntity te;

    /**
     * 返回菜单绑定的终端方块实体。
     */
    @Override
    public StorageTerminalBlockEntity storageConsolidator$getTerminal() {
        return te;
    }
}
