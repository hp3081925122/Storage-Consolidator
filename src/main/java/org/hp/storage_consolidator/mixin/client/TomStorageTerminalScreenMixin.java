package org.hp.storage_consolidator.mixin.client;

import com.tom.storagemod.gui.GuiButton;
import com.tom.storagemod.gui.StorageTerminalScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.hp.storage_consolidator.Storage_consolidator;
import org.hp.storage_consolidator.network.ConsolidateRequestPayload;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 在 Tom's Storage 1.20.1 终端左侧增加一键整理按钮。 */
@OnlyIn(Dist.CLIENT)
@Mixin(value = StorageTerminalScreen.class, remap = false)
public abstract class TomStorageTerminalScreenMixin extends Screen {
    /** Tom's Storage 原有的排序按钮，用于对齐新增按钮。 */
    @Shadow
    protected GuiButton buttonSortingType;

    /** 使用 Screen 所需的标题构造混入类。 */
    protected TomStorageTerminalScreenMixin(Component title) {
        super(title);
    }

    /** 在原终端按钮创建完成后追加整理按钮。 */
    @Inject(method = "init", at = @At("TAIL"))
    private void storageConsolidator$addConsolidateButton(CallbackInfo callbackInfo) {
        if (buttonSortingType == null) {
            Storage_consolidator.LOGGER.debug(
                    "Skipped consolidate button because Tom's Storage sorting button is unavailable: screen={}",
                    getClass().getName()
            );
            return;
        }
        int buttonX = buttonSortingType.getX() - 40;
        int buttonY = buttonSortingType.getY();
        Storage_consolidator.LOGGER.debug(
                "Adding consolidate button: screen={}, sortingButton=({},{}), consolidateButton=({},{}), size={}x{}",
                getClass().getName(),
                buttonSortingType.getX(),
                buttonSortingType.getY(),
                buttonX,
                buttonY,
                36,
                32
        );
        // 客户端只发送请求，库存读写始终留在服务端。
        addRenderableWidget(Button.builder(
                Component.translatable("button.storage_consolidator.consolidate"),
                button -> {
                    Minecraft minecraft = Minecraft.getInstance();
                    if (minecraft.getConnection() != null) {
                        ConsolidateRequestPayload.sendToServer();
                    }
                }
        ).bounds(buttonX, buttonY, 36, 32).build());
    }
}
