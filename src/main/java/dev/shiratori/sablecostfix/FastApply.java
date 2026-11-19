package dev.shiratori.sablecostfix;

import dev.ryanhcode.sable.mixinterface.block_properties.BlockStateExtension;
import dev.ryanhcode.sable.physics.config.block_properties.BlockStateConditionSet;
import dev.ryanhcode.sable.physics.config.block_properties.BlockStateConditionSet.BlockStateCondition;
import dev.ryanhcode.sable.physics.config.block_properties.PhysicsBlockPropertiesDefinition;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.Property;
import org.jetbrains.annotations.Nullable;

/**
 * Sable 2.0.6 {@code PhysicsBlockPropertiesDefinitionLoader} 的索引化替换实现。
 *
 * <p><b>上游为什么慢</b>（反编译见 {@code reports/copyfoxies-sable-load-stall/REPORT.md}）：
 * 应用阶段对每个 definition 的每个 {@code BlockState} 线性扫完该 definition 的全部 {@code overrides}，
 * 每条比较还要重做 {@code StateDefinition.getProperty(String)} 查表与值字符串解析。数据侧一旦
 * 按状态全枚举（copyfoxies 2.2.0：15,325 条、两个 prism 各 5,184 条），单维度就是 5,942 万次比较。
 *
 * <p><b>两个入口都要盖住</b>（真机日志实测：只盖第一个，客户端进档仍会卡 ~12.9 s）：
 * <ol>
 *   <li>{@code applyAll()} —— 服务端每个维度初始化跑一次，内部逐 definition 调 {@code applyToBlocks}；</li>
 *   <li>{@code applyToBlocks(definition)} —— <b>客户端</b>收 {@code ClientboundPhysicsPropertyPacket}
 *       （一条 definition 一个包）后逐条调用，完全绕过 {@code applyAll()}。</li>
 * </ol>
 * 所以 mixin 打的是这两个方法：{@code applyAll} 走 {@link #applyCached}（整批 + 内容相等短路），
 * {@code applyToBlocks} 走 {@link #applyDefinition}（单条，packet 路径）。
 *
 * <p><b>这里怎么改</b>：把"每个状态扫全部条目"反过来——
 * <ol>
 *   <li>对一个 definition 的全部 override 键取属性名序列（下称 signature）；</li>
 *   <li>对每个 {@code StateDefinition}，把每条 override 的期望值一次性解析成元组，
 *       装进 {@code Map<值元组, List<覆盖属性集>>}；</li>
 *   <li>遍历状态时只算一次元组、查一次表，命中几条就应用几条。</li>
 * </ol>
 * 复杂度从 O(状态数 × 条目数) 降到 O(状态数 × 条件数 + 条目数 × 条件数)。
 *
 * <p><b>语义等价的三条约束</b>（少一条就不等价，故都在代码里显式子处理）：
 * <ul>
 *   <li>definition 之间按 {@code definitions} 列表顺序（= priority 升序）叠加，后面的覆盖前面的；</li>
 *   <li>同一个 definition 内，先应用 {@code properties()} 基线，再按 {@code overrides()} 的迭代顺序
 *       逐条应用命中的覆盖——{@link BlockPlan#match} 返回的列表保持插入顺序，即原迭代顺序；</li>
 *   <li>命中语义是"多个<b>部分</b>键可同时命中一个状态"。索引按"名字序列"分桶，只要该 definition
 *       的所有 override 键名字序列一致（本 case 全部全绑定），桶内就是精确匹配；一旦出现<b>多种</b>
 *       名字序列，{@link #collectSignature} 返回 null，这条 definition 退回上游逐状态语义
 *       （{@link #applyUpstream}），宁可慢也不猜。</li>
 * </ul>
 *
 * <p><b>重复调用</b>：{@link #applyCached} 用<b>内容相等</b>判断"这份数据已经应用过"，命中则空转。
 * 之所以不用一次性布尔标志：{@code apply(Map, ResourceManager, ProfilerFiller)} 在每次数据包重载时
 * 会 {@code definitions.clear()} 后重填，写死的标志会让 {@code /reload} 之后的新数据静默失效
 * （REPORT §7 A1 的"正确性陷阱"）。内容比较天然覆盖了这个场景：数据变了就重跑，没变就跳过。
 */
public final class FastApply {

    /** 上一次成功应用的数据集（不可变快照）。列表内容相等即视为"已应用"。 */
    private static volatile List<PhysicsBlockPropertiesDefinition> lastApplied = List.of();

    private static volatile Result lastResult = Result.NONE;

    /** 第 2~N 个维度命中"数据集未变"而空转的次数（只用于 status 展示）。 */
    private static volatile int cachedSkips;

    /** 仅供 /sablecostfix verify 取"上游参考跑"：置位期间 {@code applyToBlocks} 的注入点直接放行。 */
    private static final ThreadLocal<Boolean> BYPASS_INTERCEPTION = new ThreadLocal<>();

    private static final ValueKey EMPTY_KEY = new ValueKey(new Comparable<?>[0]);
    private static final Property<?>[] NO_PROPERTIES = new Property<?>[0];

    private FastApply() {
    }

    /** 一次 {@code applyAll()} 的统计。{@code skipped=true} 表示数据集未变，什么都没做。 */
    public record Result(int definitions, int applied, int fallbacks, long states, long entries,
                         long comparisons, boolean skipped, long nanos) {

        public static final Result NONE = new Result(0, 0, 0, 0L, 0L, 0L, true, 0L);

        /** 人类可读的一行摘要（日志与 /sablecostfix status 共用）。 */
        public String describe() {
            if (this.skipped) {
                return "no-op: %d definition(s) unchanged since last apply".formatted(this.definitions);
            }
            return String.format(Locale.ROOT,
                    "%d definition(s) (%d indexed, %d fell back), %d block state(s), %d override entr(y/ies), "
                            + "~%d linear comparison(s) avoided, %.2f ms",
                    this.definitions, this.applied - this.fallbacks, this.fallbacks, this.states,
                    this.entries, this.comparisons, this.nanos / 1_000_000.0D);
        }
    }

    /** 累加器，避免 {@link #applyDefinition} 里到处传 long[]。 */
    private static final class Stats {
        int applied;
        int fallbacks;
        long states;
        long entries;
        long comparisons;
    }

    /** 单条 definition 的处理结果：{@code handled=false} 表示调用方必须退回上游。 */
    private record Outcome(boolean handled, List<Block> blocks) {
        static final Outcome HANDLED = new Outcome(true, List.of());
    }

    /** {@code applyAll()} 的替代入口：数据集内容未变则直接空转（第 2~N 个维度走这里）。 */
    public static Result applyCached(Collection<PhysicsBlockPropertiesDefinition> definitions) {
        List<PhysicsBlockPropertiesDefinition> snapshot = List.copyOf(definitions);
        if (snapshot.equals(lastApplied)) {
            cachedSkips++;
            // 故意不覆盖 lastResult：它是"上一次真正跑了索引应用"的测量结果，/sablecostfix status 要看的是它。
            return new Result(snapshot.size(), 0, 0, 0L, 0L, 0L, true, 0L);
        }
        return apply(snapshot);
    }

    /**
     * 强制跑一遍索引应用（不做"已应用"短路）。{@code applyAll()} 的注入点与 /sablecostfix verify 都走这里。
     * 成功后刷新 {@link #lastApplied}，使后续 {@link #applyCached} 能正确空转。
     */
    public static Result apply(List<PhysicsBlockPropertiesDefinition> definitions) {
        long start = System.nanoTime();
        Stats stats = new Stats();
        for (PhysicsBlockPropertiesDefinition definition : definitions) {
            Outcome outcome = applyDefinition(definition, stats);
            if (!outcome.handled()) {
                // 条件键形状不一致：这条走上游逐状态语义。注意不能调 applyToBlocks，
                // 否则会撞上我们自己的注入点（递归），所以用 applyUpstream 复刻同样的循环。
                stats.fallbacks++;
                applyUpstream(definition, outcome.blocks());
            }
        }
        Result result = new Result(definitions.size(), stats.applied, stats.fallbacks, stats.states,
                stats.entries, stats.comparisons, false, System.nanoTime() - start);
        lastApplied = List.copyOf(definitions);
        lastResult = result;
        return result;
    }

    /**
     * 单条 definition 的索引应用（{@code applyToBlocks} 注入点与 packet 路径用）。
     *
     * @return true 表示已完整应用，注入点可以取消上游方法；false 表示调用方必须放行上游
     */
    public static boolean applyDefinition(PhysicsBlockPropertiesDefinition definition) {
        long start = System.nanoTime();
        Stats stats = new Stats();
        Outcome outcome = applyDefinition(definition, stats);
        if (outcome.handled()) {
            SableCostFix.LOGGER.debug("sable-costfix: 1 definition applied — {} block state(s), "
                            + "{} override entr(y/ies), {} linear comparison(s) avoided, {} us",
                    stats.states, stats.entries, stats.comparisons, (System.nanoTime() - start) / 1_000L);
        }
        return outcome.handled();
    }

    // ------------------------------------------------------------------ 核心

    private static Outcome applyDefinition(PhysicsBlockPropertiesDefinition definition, Stats stats) {
        List<Block> blocks = resolveBlocks(definition);
        if (blocks.isEmpty()) {
            return Outcome.HANDLED; // 选择子没解析出方块：上游同样只打一条 error 就跳过
        }
        stats.applied++;

        Optional<Map<BlockStateConditionSet, Map<ResourceLocation, Object>>> overridesOptional =
                definition.overrides();
        Map<BlockStateConditionSet, Map<ResourceLocation, Object>> overrides =
                overridesOptional.orElse(null);

        List<String> signature = null;
        Map<BlockStateConditionSet, PhysicsBlockPropertiesDefinition> overrideDefs = Map.of();
        if (overrides != null && !overrides.isEmpty()) {
            stats.entries += overrides.size();
            signature = collectSignature(overrides.keySet());
            if (signature == null) {
                // 一个 definition 里混着多种条件键形状：索引不成立，交给调用方走上游语义。
                long definitionStates = countStates(blocks);
                stats.states += definitionStates;
                stats.comparisons += definitionStates * overrides.size();
                return new Outcome(false, blocks);
            }
            // 空的属性集是 no-op（上游也会对状态数组什么都不写），提前丢掉，省一次接口调用。
            Map<BlockStateConditionSet, PhysicsBlockPropertiesDefinition> sink =
                    new LinkedHashMap<>(overrides.size());
            for (Map.Entry<BlockStateConditionSet, Map<ResourceLocation, Object>> entry : overrides.entrySet()) {
                if (!entry.getValue().isEmpty()) {
                    sink.put(entry.getKey(), synthetic(definition, entry.getValue()));
                }
            }
            overrideDefs = sink;
        }

        // 基线永远调一次，哪怕 properties() 为空：上游 sable$loadProperties() 会在这时把 BlockState
        // 的属性数组惰性分配出来（new Object[count()]）。少调一次会让"该状态从未被任何 definition
        // 命中"与"被命中但写进去的全是默认值"在数组层面出现 null vs 全 null 的差异——数值上等价，
        // 但 /sablecostfix verify 的逐槽位比对会把它当成不一致。
        PhysicsBlockPropertiesDefinition baseDefinition = synthetic(definition, definition.properties());

        for (Block block : blocks) {
            StateDefinition<Block, BlockState> stateDefinition = block.getStateDefinition();
            List<BlockState> possibleStates = stateDefinition.getPossibleStates();
            stats.states += possibleStates.size();

            if (signature == null || overrideDefs.isEmpty()) {
                for (BlockState state : possibleStates) {
                    applyTo(state, stateDefinition, baseDefinition);
                }
                continue;
            }

            stats.comparisons += (long) possibleStates.size() * overrides.size();
            BlockPlan plan = BlockPlan.build(stateDefinition, signature, overrideDefs);
            for (BlockState state : possibleStates) {
                applyTo(state, stateDefinition, baseDefinition);
                List<PhysicsBlockPropertiesDefinition> matched = plan.match(state);
                if (matched == null) {
                    continue;
                }
                for (PhysicsBlockPropertiesDefinition override : matched) {
                    applyTo(state, stateDefinition, override);
                }
            }
        }
        return Outcome.HANDLED;
    }

    /**
     * 上游 {@code applyToBlocks} 的逐状态等价实现（用于多形状 definition 与 verify 的参考跑）。
     *
     * <p>直接调 {@code applyToBlocks} 会被我们自己的注入点接住，所以这里把它那 5 行循环复刻一遍：
     * 对每个状态把<b>完整</b> definition（基线 + 全部 overrides）交给 {@code sable$loadProperties}，
     * 与上游逐状态调用完全一致。
     */
    public static void applyUpstream(PhysicsBlockPropertiesDefinition definition, List<Block> blocks) {
        for (Block block : blocks) {
            StateDefinition<Block, BlockState> stateDefinition = block.getStateDefinition();
            for (BlockState state : stateDefinition.getPossibleStates()) {
                applyTo(state, stateDefinition, definition);
            }
        }
    }

    // ------------------------------------------------------------------ 注入点开关（仅 verify 用）

    public static boolean bypassingInterception() {
        return Boolean.TRUE.equals(BYPASS_INTERCEPTION.get());
    }

    public static void withInterceptionBypassed(Runnable action) {
        BYPASS_INTERCEPTION.set(Boolean.TRUE);
        try {
            action.run();
        } finally {
            BYPASS_INTERCEPTION.remove();
        }
    }

    public static Result lastResult() {
        return lastResult;
    }

    public static int cachedSkips() {
        return cachedSkips;
    }

    public static boolean isApplied(List<PhysicsBlockPropertiesDefinition> definitions) {
        return List.copyOf(definitions).equals(lastApplied);
    }

    // ------------------------------------------------------------------ 选择子 -> 方块

    /**
     * 与上游 {@code applyToBlocks} 的 selector 解析逐行等价（tag 或单方块，失败打同一条 error）。
     * 唯一差异：我们的日志走本模组的 logger，因此可以在 grep 时和上游那条区分开。
     */
    public static List<Block> resolveBlocks(PhysicsBlockPropertiesDefinition definition) {
        ExtraCodecs.TagOrElementLocation selector = definition.selector();
        List<Block> blocks = new ArrayList<>(4);
        if (selector.tag()) {
            TagKey<Block> tagKey = TagKey.create(Registries.BLOCK, selector.id());
            Optional<HolderSet.Named<Block>> tag = BuiltInRegistries.BLOCK.getTag(tagKey);
            if (tag.isPresent()) {
                for (Holder<Block> holder : tag.get()) {
                    blocks.add(holder.value());
                }
            } else {
                SableCostFix.LOGGER.error("Failed to apply tag physics properties. Unknown tag: {}", selector.id());
            }
        } else if (BuiltInRegistries.BLOCK.containsKey(selector.id())) {
            blocks.add(BuiltInRegistries.BLOCK.get(selector.id()));
        } else {
            SableCostFix.LOGGER.error("Failed to apply tag physics properties. Unknown block: {}", selector.id());
        }
        return blocks;
    }

    private static long countStates(List<Block> blocks) {
        long total = 0L;
        for (Block block : blocks) {
            total += block.getStateDefinition().getPossibleStates().size();
        }
        return total;
    }

    // ------------------------------------------------------------------ 索引

    /**
     * 取全部 override 键的属性名序列。所有键形状一致时返回该序列（可能为空列表），否则返回 null。
     *
     * <p>顺序敏感是故意的：值元组按位置解析，顺序不一致的键无法共用一个桶。
     */
    @Nullable
    private static List<String> collectSignature(Collection<BlockStateConditionSet> keys) {
        List<String> signature = null;
        for (BlockStateConditionSet key : keys) {
            List<BlockStateCondition> conditions = key.blockStateConditions();
            List<String> names = new ArrayList<>(conditions.size());
            for (BlockStateCondition condition : conditions) {
                names.add(condition.property());
            }
            if (signature == null) {
                signature = names;
            } else if (!signature.equals(names)) {
                return null;
            }
        }
        return signature == null ? List.of() : signature;
    }

    /** 一个方块状态的取值 -> 命中条目的索引。{@link #EMPTY} 表示该方块上没有任何键能命中。 */
    private static final class BlockPlan {

        static final BlockPlan EMPTY = new BlockPlan(NO_PROPERTIES, Map.of());

        private final Property<?>[] properties;
        private final Map<ValueKey, List<PhysicsBlockPropertiesDefinition>> buckets;

        private BlockPlan(Property<?>[] properties,
                          Map<ValueKey, List<PhysicsBlockPropertiesDefinition>> buckets) {
            this.properties = properties;
            this.buckets = buckets;
        }

        static BlockPlan build(StateDefinition<Block, BlockState> stateDefinition, List<String> signature,
                               Map<BlockStateConditionSet, PhysicsBlockPropertiesDefinition> entries) {
            if (signature.isEmpty()) {
                // 无条件的键对任何状态都成立：一个桶装全部，顺序即 entries 的迭代顺序。
                return new BlockPlan(NO_PROPERTIES, Map.of(EMPTY_KEY, List.copyOf(entries.values())));
            }

            Property<?>[] properties = new Property<?>[signature.size()];
            for (int i = 0; i < properties.length; i++) {
                Property<?> property = stateDefinition.getProperty(signature.get(i));
                if (property == null) {
                    // 该方块没有这个属性：上游 matches() 逐条返回 false，等价于"一条都命中不了"。
                    return EMPTY;
                }
                properties[i] = property;
            }

            Map<ValueKey, List<PhysicsBlockPropertiesDefinition>> buckets = new HashMap<>(entries.size() * 2);
            for (Map.Entry<BlockStateConditionSet, PhysicsBlockPropertiesDefinition> entry : entries.entrySet()) {
                List<BlockStateCondition> conditions = entry.getKey().blockStateConditions();
                Comparable<?>[] tuple = new Comparable<?>[properties.length];
                boolean resolvable = true;
                for (int i = 0; i < properties.length; i++) {
                    // 值字符串只在这里解析一次，否则每条比较都要重解析（上游正是这么做的）。
                    Object expected = expectedValue(properties[i], conditions.get(i).value());
                    if (expected == null) {
                        resolvable = false; // 非法值：上游 matches() 返回 false，此条目永不命中
                        break;
                    }
                    tuple[i] = (Comparable<?>) expected;
                }
                if (!resolvable) {
                    continue;
                }
                buckets.computeIfAbsent(new ValueKey(tuple), key -> new ArrayList<>(1)).add(entry.getValue());
            }
            return new BlockPlan(properties, buckets);
        }

        @Nullable
        List<PhysicsBlockPropertiesDefinition> match(BlockState state) {
            if (this.properties.length == 0) {
                return this.buckets.get(EMPTY_KEY);
            }
            Comparable<?>[] tuple = new Comparable<?>[this.properties.length];
            for (int i = 0; i < this.properties.length; i++) {
                tuple[i] = stateValue(state, this.properties[i]);
            }
            return this.buckets.get(new ValueKey(tuple));
        }
    }

    /** 值元组 → 命中列表。元组元素一律是不可变值（枚举/Boolean/Integer/String），可安全做哈希键。 */
    private static final class ValueKey {

        private final Comparable<?>[] values;
        private final int hash;

        ValueKey(Comparable<?>[] values) {
            this.values = values;
            this.hash = Arrays.hashCode(values);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof ValueKey key && this.hash == key.hash && Arrays.equals(this.values, key.values);
        }

        @Override
        public int hashCode() {
            return this.hash;
        }
    }

    // ------------------------------------------------------------------ 应用

    /** 借上游自己的写入口：带 {@code Optional.empty()} overrides 的 definition 只会写 properties()。 */
    private static void applyTo(BlockState state, StateDefinition<Block, BlockState> stateDefinition,
                                PhysicsBlockPropertiesDefinition definition) {
        ((BlockStateExtension) state).sable$loadProperties(stateDefinition, definition);
    }

    private static PhysicsBlockPropertiesDefinition synthetic(PhysicsBlockPropertiesDefinition source,
                                                              Map<ResourceLocation, Object> properties) {
        return new PhysicsBlockPropertiesDefinition(source.selector(), source.priority(), properties,
                Optional.empty());
    }

    private static <T extends Comparable<T>> T stateValue(BlockState state, Property<T> property) {
        return state.getValue(property);
    }

    @Nullable
    private static <T extends Comparable<T>> Object expectedValue(Property<T> property, String name) {
        return property.getValue(name).orElse(null);
    }
}
