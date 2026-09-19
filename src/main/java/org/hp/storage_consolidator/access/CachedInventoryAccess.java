package org.hp.storage_consolidator.access;

import java.util.function.Supplier;
import net.neoforged.neoforge.items.IItemHandler;

/** 暴露缓存包装的底层身份，保留外层过滤器执行入口。 */
public interface CachedInventoryAccess {
    Supplier<? extends IItemHandler> storageConsolidator$getWrappedHandler();
}
