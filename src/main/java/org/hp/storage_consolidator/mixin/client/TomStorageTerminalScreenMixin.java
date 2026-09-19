package org.hp.storage_consolidator.mixin.client;

import com.tom.storagemod.screen.AbstractStorageTerminalScreen;
import com.tom.storagemod.screen.widget.EnumCycleButton;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.hp.storage_consolidator.network.ConsolidateRequestPayload;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 在 Tom's Storage 终端左侧增加一键整理按钮。
 */
@OnlyIn(Dist.CLIENT)
@Mixin(value = AbstractStorageTerminalScreen.class, remap = false)
public abstract class TomStorageTerminalScreenMixin extends Screen {
    @Shadow
    protected EnumCycleButton<?> buttonSortingType;

    /**
     * 在 Tom's Storage 原有控制按钮之后加入整理按钮。
     */
    protected TomStorageTerminalScreenMixin(Component title) {
        super(title);
    }

    /**
     * 点击按钮时仅发送请求，所有库存读取和变更都在服务端执行。
     */
    @Inject(method = "init", at = @At("TAIL"))
    private void storageConsolidator$addConsolidateButton(CallbackInfo callbackInfo) {
        addRenderableWidget(Button.builder(
                Component.translatable("button.storage_consolidator.consolidate"),
                button -> {
                    Minecraft minecraft = Minecraft.getInstance();
                    if (minecraft.getConnection() != null) {
                        minecraft.getConnection().send(new ServerboundCustomPayloadPacket(ConsolidateRequestPayload.INSTANCE));
                    }
                }
        ).bounds(buttonSortingType.getX() - 24, buttonSortingType.getY(), 20, 20).build());
    }
}
