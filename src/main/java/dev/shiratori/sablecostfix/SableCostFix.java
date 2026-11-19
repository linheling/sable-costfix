package dev.shiratori.sablecostfix;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.slf4j.Logger;

/**
 * 入口类。除注册 {@code /sablecostfix} 外不做任何事——真正的接管在
 * {@code mixin/PhysicsLoaderMixin}（{@code @Inject} 进 Sable 的
 * {@code PhysicsBlockPropertiesDefinitionLoader#applyAll}）与 {@link FastApply}。
 */
@Mod(SableCostFix.MOD_ID)
public final class SableCostFix {

    public static final String MOD_ID = "sablecostfix";
    public static final Logger LOGGER = LogUtils.getLogger();

    public SableCostFix(IEventBus modEventBus) {
        // RegisterCommandsEvent 是游戏总线事件（不是 IModBusEvent），只能挂 NeoForge.EVENT_BUS。
        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);
        LOGGER.info("[{}] armed: hash-indexed physics_block_properties apply for sable 2.0.x", MOD_ID);
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        CostFixCommand.register(event.getDispatcher());
    }
}
