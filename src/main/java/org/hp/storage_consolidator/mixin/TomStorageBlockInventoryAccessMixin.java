package org.hp.storage_consolidator.mixin;

import com.tom.storagemod.inventory.PlatformInventoryAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.neoforged.neoforge.capabilities.BlockCapabilityCache;
import net.neoforged.neoforge.items.IItemHandler;
import org.hp.storage_consolidator.access.TomStorageBlockPositionAccess;
import org.hp.storage_consolidator.Storage_consolidator;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * 暴露普通 Tom's Storage 库存访问器绑定的方块位置，用于高风险模组筛选。
 */
@Mixin(value = PlatformInventoryAccess.BlockInventoryAccess.class, remap = false)
public abstract class TomStorageBlockInventoryAccessMixin implements TomStorageBlockPositionAccess {
    private static final String INVALID_CACHE_MESSAGE =
            "Do not call getCapability on an invalid cache or from the invalidation listener!";

    private static long lastInvalidCacheLogTime = -20L;

    @Shadow
    private BlockCapabilityCache<IItemHandler, Direction> itemCache;

    /**
     * 返回能力缓存对应的方块位置。
     */
    @Override
    public BlockPos storageConsolidator$getPosition() {
        return itemCache == null ? null : itemCache.pos();
    }

    /**
     * 在 NeoForge 能力缓存失效通知期间，将 Tom's Storage 的非法读取视为暂时没有库存。
     */
    @Redirect(
            method = {"get", "exists"},
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/neoforged/neoforge/capabilities/BlockCapabilityCache;getCapability()Ljava/lang/Object;"
            )
    )
    private Object storageConsolidator$readCapabilitySafely(BlockCapabilityCache<?, ?> cache) {
        try {
            return cache.getCapability();
        } catch (IllegalStateException exception) {
            if (!INVALID_CACHE_MESSAGE.equals(exception.getMessage())) {
                throw exception;
            }

            // 限制调试日志频率，避免大型压力场景因重复日志进一步拖慢服务端。
            long gameTime = cache.level().getGameTime();
            if (gameTime - lastInvalidCacheLogTime >= 20) {
                lastInvalidCacheLogTime = gameTime;
                Storage_consolidator.LOGGER.debug(
                        "Skipped an invalid Tom's Storage capability cache at {}: {}",
                        cache.pos(),
                        exception.getMessage()
                );
            }
            return null;
        }
    }
}
