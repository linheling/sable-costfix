package dev.shiratori.sablecostfix;

import com.mojang.brigadier.CommandDispatcher;
import dev.ryanhcode.sable.physics.config.block_properties.BlockStateConditionSet;
import dev.ryanhcode.sable.physics.config.block_properties.PhysicsBlockPropertiesDefinition;
import dev.ryanhcode.sable.physics.config.block_properties.PhysicsBlockPropertiesDefinitionLoader;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * {@code /sablecostfix status|verify}。
 *
 * <p>{@code verify} 是本补丁唯一的"等价性证明"，而且<b>两条注入路径都验</b>：
 * <ol>
 *   <li>参考跑：绕过注入，逐条调上游 {@code applyToBlocks}，快照每个状态的属性数组；</li>
 *   <li>applyAll 路径：清空后用 {@link FastApply#apply} 重跑（服务端维度初始化的那条）；</li>
 *   <li>packet 路径：清空后逐条调 {@code applyToBlocks}（客户端收 {@code ClientboundPhysicsPropertyPacket}
 *       的那条，现在会被注入点接住）。</li>
 * </ol>
 * 2、3 各自与 1 逐槽位比对。数据侧全枚举时参考跑一次约 18 s，所以这是显式命令而不是自动检查。
 *
 * <p>比对的是 {@code BlockState} 上 Sable 加的那个 {@code Object[]}（按类型反射定位，不写死
 * {@code sable$properties}），不是 {@code sable$getProperty()} 的返回值：后者会把 null 兜底成默认值，
 * 掩盖"一遍写了、另一遍没写"。
 */
public final class CostFixCommand {

    private CostFixCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("sablecostfix")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("status").executes(context -> status(context.getSource())))
                .then(Commands.literal("verify").executes(context -> verify(context.getSource()))));
    }

    // ------------------------------------------------------------------ status

    private static int status(CommandSourceStack source) {
        List<PhysicsBlockPropertiesDefinition> definitions =
                List.copyOf(PhysicsBlockPropertiesDefinitionLoader.INSTANCE.getDefinitions());
        int withOverrides = 0;
        long entries = 0L;
        for (PhysicsBlockPropertiesDefinition definition : definitions) {
            Optional<Map<BlockStateConditionSet, Map<ResourceLocation, Object>>> overrides = definition.overrides();
            if (overrides.isPresent() && !overrides.get().isEmpty()) {
                withOverrides++;
                entries += overrides.get().size();
            }
        }
        boolean applied = FastApply.isApplied(definitions);
        String message = ("sable-costfix: %d definition(s), %d with overrides (%d entr(y/ies)); "
                + "cache %s, %d applyAll call(s) skipped as unchanged; last: %s")
                .formatted(definitions.size(), withOverrides, entries,
                        applied ? "warm (next applyAll is a no-op)" : "cold (next applyAll re-indexes)",
                        FastApply.cachedSkips(), FastApply.lastResult().describe());
        source.sendSuccess(() -> Component.literal(message), false);
        return 1;
    }

    // ------------------------------------------------------------------ verify

    private static int verify(CommandSourceStack source) {
        List<PhysicsBlockPropertiesDefinition> definitions =
                List.copyOf(PhysicsBlockPropertiesDefinitionLoader.INSTANCE.getDefinitions());
        if (definitions.isEmpty()) {
            source.sendFailure(Component.literal(
                    "sable-costfix: no physics_block_properties definitions loaded yet (join a world first)"));
            return 0;
        }
        Field field = propertiesField();
        if (field == null) {
            source.sendFailure(Component.literal(
                    "sable-costfix: BlockState property array not found; cannot verify against this Sable build"));
            return 0;
        }

        Set<BlockState> targets = new LinkedHashSet<>();
        for (PhysicsBlockPropertiesDefinition definition : definitions) {
            for (Block block : FastApply.resolveBlocks(definition)) {
                targets.addAll(block.getStateDefinition().getPossibleStates());
            }
        }
        if (targets.isEmpty()) {
            source.sendFailure(Component.literal("sable-costfix: no block states targeted by any definition"));
            return 0;
        }

        source.sendSuccess(() -> Component.literal(("sable-costfix: verifying %d state(s) — running Sable's "
                + "original pass once (the slow path), then the applyAll path and the packet path, comparing "
                + "slot by slot ...").formatted(targets.size())), false);

        // 1) 参考跑：绕过我们的注入点，逐条走上游 applyToBlocks
        Map<BlockState, Object[]> reference = new IdentityHashMap<>();
        long originalNanos;
        try {
            reset(field, targets);
            long start = System.nanoTime();
            FastApply.withInterceptionBypassed(() -> {
                for (PhysicsBlockPropertiesDefinition definition : definitions) {
                    PhysicsBlockPropertiesDefinitionLoader.applyToBlocks(definition);
                }
            });
            originalNanos = System.nanoTime() - start;
            for (BlockState state : targets) {
                reference.put(state, (Object[]) field.get(state));
            }
        } catch (Throwable throwable) {
            SableCostFix.LOGGER.error("sable-costfix: verification reference pass failed", throwable);
            FastApply.apply(definitions);
            source.sendFailure(Component.literal("sable-costfix: verification aborted: " + throwable));
            return 0;
        }

        // 2) applyAll 路径（服务端维度初始化）
        int applyAllMismatches;
        long applyAllNanos;
        try {
            reset(field, targets);
            long start = System.nanoTime();
            FastApply.apply(definitions);
            applyAllNanos = System.nanoTime() - start;
            applyAllMismatches = countMismatches(targets, reference, field);
        } catch (Throwable throwable) {
            SableCostFix.LOGGER.error("sable-costfix: verification applyAll pass failed", throwable);
            FastApply.apply(definitions);
            source.sendFailure(Component.literal("sable-costfix: verification aborted: " + throwable));
            return 0;
        }

        // 3) packet 路径（客户端收包后逐条 applyToBlocks，现在会命中注入点）
        int packetMismatches;
        long packetNanos;
        try {
            reset(field, targets);
            long start = System.nanoTime();
            for (PhysicsBlockPropertiesDefinition definition : definitions) {
                PhysicsBlockPropertiesDefinitionLoader.applyToBlocks(definition);
            }
            packetNanos = System.nanoTime() - start;
            packetMismatches = countMismatches(targets, reference, field);
        } catch (Throwable throwable) {
            SableCostFix.LOGGER.error("sable-costfix: verification packet pass failed", throwable);
            FastApply.apply(definitions);
            source.sendFailure(Component.literal("sable-costfix: verification aborted: " + throwable));
            return 0;
        }

        int mismatches = applyAllMismatches + packetMismatches;
        String verdict = mismatches == 0 ? "PASS" : "FAIL";
        String summary = String.format(Locale.ROOT,
                "sable-costfix: verify %s — %d state(s) compared; original %.2f s vs applyAll %.3f s / "
                        + "packet %.3f s; mismatches applyAll=%d packet=%d",
                verdict, targets.size(), originalNanos / 1.0e9D, applyAllNanos / 1.0e9D,
                packetNanos / 1.0e9D, applyAllMismatches, packetMismatches);
        source.sendSuccess(() -> Component.literal(summary), true);
        SableCostFix.LOGGER.info(summary);

        if (mismatches != 0) {
            List<String> samples = new ArrayList<>();
            try {
                for (BlockState state : targets) {
                    Object[] expected = reference.get(state);
                    Object[] actual = (Object[]) field.get(state);
                    if (!Arrays.equals(expected, actual) && samples.size() < 5) {
                        samples.add(state + " expected=" + Arrays.toString(expected)
                                + " actual=" + Arrays.toString(actual));
                    }
                }
            } catch (IllegalAccessException ignored) {
                // 只影响样本展示
            }
            for (String sample : samples) {
                SableCostFix.LOGGER.warn("sable-costfix: mismatch: {}", sample);
            }
            String joined = String.join(" | ", samples);
            source.sendSuccess(() -> Component.literal("sable-costfix: first mismatch(es): " + joined), false);
        }
        return mismatches == 0 ? 1 : 0;
    }

    private static int countMismatches(Set<BlockState> targets, Map<BlockState, Object[]> reference, Field field)
            throws IllegalAccessException {
        int mismatches = 0;
        for (BlockState state : targets) {
            if (!Arrays.equals(reference.get(state), (Object[]) field.get(state))) {
                mismatches++;
            }
        }
        return mismatches;
    }

    // ------------------------------------------------------------------ 反射

    private static void reset(Field field, Set<BlockState> states) throws IllegalAccessException {
        for (BlockState state : states) {
            field.set(state, null);
        }
    }

    /**
     * 定位 Sable {@code BlockStateMixin} 加到 {@code BlockState} 上的 {@code Object[]} 字段。
     *
     * <p>不写死 {@code sable$properties}：{@code @Unique} 成员在部分 Mixin 配置下会被改名，
     * 按"类型 + 名字线索"找更稳。整个 {@code BlockState} 只有这一个 {@code Object[]} 字段。
     */
    @Nullable
    private static Field propertiesField() {
        Field fallback = null;
        for (Field candidate : BlockState.class.getDeclaredFields()) {
            if (candidate.getType() != Object[].class) {
                continue;
            }
            try {
                candidate.setAccessible(true);
            } catch (RuntimeException ignored) {
                continue;
            }
            String name = candidate.getName();
            if (name.contains("propert") || name.contains("sable")) {
                return candidate;
            }
            if (fallback == null) {
                fallback = candidate;
            }
        }
        return fallback;
    }
}
