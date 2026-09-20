package org.hp.storage_consolidator.network;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;
import org.hp.storage_consolidator.StorageConsolidatorService;
import org.hp.storage_consolidator.Storage_consolidator;

import java.util.function.Supplier;

/** 客户端点击整理按钮后发送的无参数 Forge 网络消息。 */
public final class ConsolidateRequestPayload {
    /** Forge 1.20.1 的简单网络通道。 */
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(Storage_consolidator.MODID, "main"),
            () -> "1",
            "1"::equals,
            "1"::equals
    );

    /** 禁止实例化消息工具类。 */
    private ConsolidateRequestPayload() {
    }

    /** 注册无参数的客户端到服务端消息。 */
    public static void register() {
        // 使用固定消息编号，保持同一模组版本的协议稳定。
        CHANNEL.registerMessage(
                0,
                ConsolidateRequestPayloadMarker.class,
                (message, buffer) -> {
                },
                buffer -> new ConsolidateRequestPayloadMarker(),
                ConsolidateRequestPayload::handle
        );
    }

    /** 发送整理请求到服务端。 */
    public static void sendToServer() {
        CHANNEL.sendToServer(new ConsolidateRequestPayloadMarker());
    }

    /** 处理服务端收到的整理请求。 */
    private static void handle(ConsolidateRequestPayloadMarker message, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player != null) {
                StorageConsolidatorService.consolidate(player);
            }
        });
        context.setPacketHandled(true);
    }

    /** SimpleChannel 要求可实例化的消息类型。 */
    public static final class ConsolidateRequestPayloadMarker {
        /** 创建一个空消息。 */
        public ConsolidateRequestPayloadMarker() {
        }
    }
}
