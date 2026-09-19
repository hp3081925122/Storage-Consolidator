package org.hp.storage_consolidator.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.hp.storage_consolidator.Storage_consolidator;
import org.hp.storage_consolidator.StorageConsolidatorService;

/**
 * 客户端点击整理按钮后发送的无参数服务端请求。
 */
public record ConsolidateRequestPayload() implements CustomPacketPayload {
    public static final ConsolidateRequestPayload INSTANCE = new ConsolidateRequestPayload();
    public static final Type<ConsolidateRequestPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Storage_consolidator.MODID, "consolidate_request")
    );
    public static final StreamCodec<RegistryFriendlyByteBuf, ConsolidateRequestPayload> STREAM_CODEC =
            StreamCodec.unit(INSTANCE);

    /**
     * 返回当前网络负载类型。
     */
    @Override
    public Type<ConsolidateRequestPayload> type() {
        return TYPE;
    }

    /**
     * 在服务端主线程执行整理，并由整理服务再次验证菜单和距离。
     */
    public static void handle(ConsolidateRequestPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                StorageConsolidatorService.consolidate(player);
            }
        });
    }
}
