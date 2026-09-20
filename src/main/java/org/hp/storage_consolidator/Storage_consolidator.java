package org.hp.storage_consolidator;

import com.mojang.logging.LogUtils;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import org.hp.storage_consolidator.command.StressTestCommand;
import org.hp.storage_consolidator.network.ConsolidateRequestPayload;
import org.slf4j.Logger;

/** Storage Consolidator 的 1.20.1 Forge 模组入口。 */
@Mod(Storage_consolidator.MODID)
public final class Storage_consolidator {
    /** 模组注册名。 */
    public static final String MODID = "storage_consolidator";
    /** 统一日志记录器。 */
    public static final Logger LOGGER = LogUtils.getLogger();

    /** 注册配置、网络包、命令和服务端整理 tick。 */
    public Storage_consolidator() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, Config.SPEC);
        ConsolidateRequestPayload.register();
        MinecraftForge.EVENT_BUS.addListener(StressTestCommand::register);
        MinecraftForge.EVENT_BUS.addListener(StorageConsolidatorService::onServerTick);
        MinecraftForge.EVENT_BUS.addListener(StorageConsolidatorService::onServerStopped);
    }
}
