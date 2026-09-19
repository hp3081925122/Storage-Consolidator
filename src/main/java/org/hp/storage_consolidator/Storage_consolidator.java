package org.hp.storage_consolidator;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.hp.storage_consolidator.command.StressTestCommand;
import org.hp.storage_consolidator.network.ConsolidateRequestPayload;
import org.slf4j.Logger;

/**
 * Storage Consolidator 的 NeoForge 模组入口。
 */
@Mod(Storage_consolidator.MODID)
public final class Storage_consolidator {
    public static final String MODID = "storage_consolidator";
    public static final Logger LOGGER = LogUtils.getLogger();

    /**
     * 注册配置和整理请求的网络负载。
     */
    public Storage_consolidator(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
        modEventBus.addListener(Storage_consolidator::registerPayloads);
        NeoForge.EVENT_BUS.addListener(StressTestCommand::register);
        // 按服务端 tick 推进整理，并在世界退出后释放任务。
        NeoForge.EVENT_BUS.addListener(StorageConsolidatorService::onServerTick);
        // 记录服务端主体开始时间，用于区分整理耗时与其他服务端工作。
        NeoForge.EVENT_BUS.addListener(StorageConsolidatorService::onServerTickPre);
        NeoForge.EVENT_BUS.addListener(StorageConsolidatorService::onServerStopped);
    }

    /**
     * 注册客户端发往服务端的整理请求。
     */
    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToServer(
                ConsolidateRequestPayload.TYPE,
                ConsolidateRequestPayload.STREAM_CODEC,
                ConsolidateRequestPayload::handle
        );
    }
}
