package org.hp.storage_consolidator.mixin;

import java.util.function.Supplier;
import net.neoforged.neoforge.items.IItemHandler;
import org.hp.storage_consolidator.access.CachedInventoryAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 可选模组不存在时不应用此包装身份适配。 */
@Pseudo
@Mixin(targets = "net.p3pp3rf1y.sophisticatedcore.inventory.CachedFailedInsertInventoryHandler", remap = false)
public interface SophisticatedCachedInventoryMixin extends CachedInventoryAccess {
    /** 读取当前供应器，不能缓存可能失效的底层库存。 */
    @Override
    @Accessor("wrappedHandlerGetter")
    Supplier<? extends IItemHandler> storageConsolidator$getWrappedHandler();
}
