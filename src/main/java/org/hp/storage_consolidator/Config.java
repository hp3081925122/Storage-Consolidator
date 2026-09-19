package org.hp.storage_consolidator;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Storage Consolidator 的公共配置。
 */
public final class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    private static final ModConfigSpec.ConfigValue<java.util.List<? extends String>> BLOCKED_SOURCE_MOD_IDS =
            BUILDER.comment(
                    "默认排除 Refined Storage 和 AE2 这类网络仓储，其他可解析库存来源默认启用。",
                    "如需额外跳过某个模组，将其模组 ID 写入 blockedSourceModIds；删除默认 ID 可重新启用对应来源。"
            ).defineListAllowEmpty(
                    "blockedSourceModIds",
                    java.util.List.of("refinedstorage", "ae2"),
                    Config::isValidModId
            );

    private static final ModConfigSpec.BooleanValue SKIP_UNKNOWN_SOURCES =
            BUILDER.comment(
                    "无法解析到真实方块位置的库存是否跳过。开启可以避免处理未知的虚拟库存。",
                    "Whether to skip inventories whose real block position cannot be resolved. Enable this to avoid unknown virtual inventories."
            ).define("skipUnknownSources", true);

    public static final ModConfigSpec SPEC = BUILDER.build();

    public static Set<String> blockedSourceModIds = Collections.emptySet();
    public static boolean skipUnknownSources = true;

    private Config() {
    }

    /**
     * 检查配置中的模组命名空间格式。
     */
    private static boolean isValidModId(Object value) {
        return value instanceof String modId && modId.matches("[a-z0-9_.-]+");
    }

    /**
     * 读取 NeoForge 配置值并统一命名空间大小写。
     */
    @SubscribeEvent
    static void onLoad(ModConfigEvent event) {
        // 仅响应本模组配置，并更新库存过滤规则。
        if (event.getConfig().getSpec() != SPEC) {
            return;
        }
        blockedSourceModIds = BLOCKED_SOURCE_MOD_IDS.get().stream()
                .map(modId -> modId.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
        skipUnknownSources = SKIP_UNKNOWN_SOURCES.get();
    }
}
