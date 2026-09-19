package org.hp.storage_consolidator.mixin;

import com.tom.storagemod.inventory.PlatformMultiInventoryAccess;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.resource.Resource;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import org.hp.storage_consolidator.Storage_consolidator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.concurrent.TimeUnit;

/** 防止 Tom's Storage 多库存缓存短暂失效时把无槽处理器当作有效槽位访问。 */
@SuppressWarnings("rawtypes")
@Mixin(value = PlatformMultiInventoryAccess.class, remap = false)
public abstract class TomStorageMultiInventoryAccessMixin {
    private static long storageConsolidator$lastGuardLogNanos;

    /** 低频记录失效槽位，避免大型库存网络因异常路径刷满日志。 */
    private static void storageConsolidator$logGuardedAccess(String operation, ResourceHandler handler, int index) {
        long now = System.nanoTime();
        if (now - storageConsolidator$lastGuardLogNanos < TimeUnit.SECONDS.toNanos(5)) {
            return;
        }
        storageConsolidator$lastGuardLogNanos = now;
        Storage_consolidator.LOGGER.debug(
                "Guarded invalid Tom's Storage multi-inventory access: operation={}, handler={}, index={}",
                operation,
                handler.getClass().getName(),
                index
        );
    }

    /** 失效槽位没有数量，避免 EmptyResourceHandler 抛出索引异常。 */
    @Redirect(
            method = "getAmountAsLong",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/neoforged/neoforge/transfer/ResourceHandler;getAmountAsLong(I)J"
            )
    )
    private static long storageConsolidator$readAmountSafely(ResourceHandler handler, int index) {
        try {
            return handler.getAmountAsLong(index);
        } catch (IndexOutOfBoundsException exception) {
            storageConsolidator$logGuardedAccess("amount", handler, index);
            return 0L;
        }
    }

    /** 失效槽位没有资源，避免后续搬运继续使用非法索引。 */
    @Redirect(
            method = "getResource",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/neoforged/neoforge/transfer/ResourceHandler;getResource(I)Lnet/neoforged/neoforge/transfer/resource/Resource;"
            )
    )
    private static Resource storageConsolidator$readResourceSafely(ResourceHandler handler, int index) {
        try {
            return handler.getResource(index);
        } catch (IndexOutOfBoundsException exception) {
            storageConsolidator$logGuardedAccess("resource", handler, index);
            return ItemResource.EMPTY;
        }
    }

    /** 失效槽位没有容量，交给调用方按不可用槽位处理。 */
    @Redirect(
            method = "getCapacityAsLong",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/neoforged/neoforge/transfer/ResourceHandler;getCapacityAsLong(ILnet/neoforged/neoforge/transfer/resource/Resource;)J"
            )
    )
    private static long storageConsolidator$readCapacitySafely(ResourceHandler handler, int index, Resource resource) {
        try {
            return handler.getCapacityAsLong(index, resource);
        } catch (IndexOutOfBoundsException exception) {
            storageConsolidator$logGuardedAccess("capacity", handler, index);
            return 0L;
        }
    }

    /** 失效槽位不接受物品，阻止 Hopper 继续向空处理器写入。 */
    @Redirect(
            method = "isValid",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/neoforged/neoforge/transfer/ResourceHandler;isValid(ILnet/neoforged/neoforge/transfer/resource/Resource;)Z"
            )
    )
    private static boolean storageConsolidator$checkValiditySafely(ResourceHandler handler, int index, Resource resource) {
        try {
            return handler.isValid(index, resource);
        } catch (IndexOutOfBoundsException exception) {
            storageConsolidator$logGuardedAccess("validity", handler, index);
            return false;
        }
    }

    /** 失效槽位拒绝插入，保持 NeoForge 事务语义下的无变化结果。 */
    @Redirect(
            method = "insert",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/neoforged/neoforge/transfer/ResourceHandler;insert(ILnet/neoforged/neoforge/transfer/resource/Resource;ILnet/neoforged/neoforge/transfer/transaction/TransactionContext;)I"
            )
    )
    private static int storageConsolidator$insertSafely(
            ResourceHandler handler,
            int index,
            Resource resource,
            int amount,
            TransactionContext transaction
    ) {
        try {
            return handler.insert(index, resource, amount, transaction);
        } catch (IndexOutOfBoundsException exception) {
            storageConsolidator$logGuardedAccess("insert", handler, index);
            return 0;
        }
    }

    /** 失效槽位拒绝抽取，保持 NeoForge 事务语义下的无变化结果。 */
    @Redirect(
            method = "extract",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/neoforged/neoforge/transfer/ResourceHandler;extract(ILnet/neoforged/neoforge/transfer/resource/Resource;ILnet/neoforged/neoforge/transfer/transaction/TransactionContext;)I"
            )
    )
    private static int storageConsolidator$extractSafely(
            ResourceHandler handler,
            int index,
            Resource resource,
            int amount,
            TransactionContext transaction
    ) {
        try {
            return handler.extract(index, resource, amount, transaction);
        } catch (IndexOutOfBoundsException exception) {
            storageConsolidator$logGuardedAccess("extract", handler, index);
            return 0;
        }
    }
}
