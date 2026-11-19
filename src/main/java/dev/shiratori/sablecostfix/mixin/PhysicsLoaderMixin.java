package dev.shiratori.sablecostfix.mixin;

import dev.ryanhcode.sable.physics.config.block_properties.PhysicsBlockPropertiesDefinition;
import dev.ryanhcode.sable.physics.config.block_properties.PhysicsBlockPropertiesDefinitionLoader;
import dev.shiratori.sablecostfix.FastApply;
import dev.shiratori.sablecostfix.SableCostFix;
import java.util.Collection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 把 {@code PhysicsBlockPropertiesDefinitionLoader} 的两个应用入口都换成索引实现。
 *
 * <p><b>为什么是两个方法</b>（2026-11-19 真机日志实测）：
 * <ul>
 *   <li>{@code applyAll()} ← 服务端 {@code Sable.defaultSubLevelContainerInitializer}，每维度一次；</li>
 *   <li>{@code applyToBlocks(definition)} ← <b>客户端</b> {@code ClientboundPhysicsPropertyPacket.handle()}
 *       （一条 definition 一个包），完全绕过 {@code applyAll()}。只注入前者时，服务端 18.8 s/维度降到
 *       0.74 s，但客户端进档仍有一段 <b>12.9 s</b> 的 Render thread 空窗（同一段在未装补丁的会话里是
 *       12.5 s，一模一样）。</li>
 * </ul>
 *
 * <p>为什么打 {@code applyAll} 而不是只打 {@code applyToBlocks}：{@code applyAll} 是唯一持有
 * {@code definitions} 列表顺序的入口（上游在 {@code apply()} 里按 priority 排过序），在那一层接管
 * 才能"整批只跑一次 + 内容相等短路"，第 2~N 个维度直接空转。
 *
 * <p>{@code cancellable} + HEAD 而不是 {@code @Overwrite}：{@code @Overwrite} 会和任何其它同样打这两个
 * 方法的 mod 硬冲突；{@code @Inject(HEAD, cancellable)} 只是"我们先跑、跑成了就取消原方法"。
 *
 * <p>注入失败（Sable 升级改了方法名）会让 mixin 以 {@code require=1} 直接崩在启动阶段——这是刻意的：
 * 补丁静默失效比崩更难查。运行期数据异常则不崩：见两个 catch 分支（记 error，放行上游原方法）。
 */
@Mixin(value = PhysicsBlockPropertiesDefinitionLoader.class, remap = false)
public abstract class PhysicsLoaderMixin {

    @Shadow
    public abstract Collection<PhysicsBlockPropertiesDefinition> getDefinitions();

    @Inject(method = "applyAll", at = @At("HEAD"), cancellable = true, remap = false)
    private void sablecostfix$applyAll(CallbackInfo callback) {
        try {
            FastApply.Result result = FastApply.applyCached(this.getDefinitions());
            if (result.skipped()) {
                SableCostFix.LOGGER.debug("sable-costfix: {}", result.describe());
            } else {
                SableCostFix.LOGGER.info("sable-costfix: {}", result.describe());
            }
            callback.cancel();
        } catch (Throwable throwable) {
            // 走到这里说明我们的索引实现炸了。绝不 cancel：让上游原方法照常跑完，
            // 结果仍然正确，只是慢（日志里有明确 error）。
            SableCostFix.LOGGER.error(
                    "sable-costfix: indexed apply failed; falling back to Sable's original applyAll()",
                    throwable);
        }
    }

    @Inject(method = "applyToBlocks", at = @At("HEAD"), cancellable = true, remap = false)
    private static void sablecostfix$applyToBlocks(PhysicsBlockPropertiesDefinition definition,
                                                   CallbackInfo callback) {
        if (FastApply.bypassingInterception()) {
            return; // /sablecostfix verify 正在取"上游参考跑"，放行
        }
        try {
            if (FastApply.applyDefinition(definition)) {
                callback.cancel();
            }
            // 返回 false（键形状不一致）时不 cancel：上游自己跑，语义正确只是慢。
        } catch (Throwable throwable) {
            SableCostFix.LOGGER.error(
                    "sable-costfix: indexed applyToBlocks failed; falling back to Sable's original",
                    throwable);
        }
    }
}
