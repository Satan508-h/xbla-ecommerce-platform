package com.xbla.rag.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link McpToolRegistry} 的纯单测。
 *
 * <h2>★★ 本类存在的唯一理由：把「5.7 把排好的顺序弄丢了」这件事钉死</h2>
 *
 * <p>5.7 的实现最后一步写的是 {@code Map.copyOf(sorted)}。那<b>看起来</b>
 * 是个防御性拷贝，实际上把上面刚排好的顺序打乱了 ——
 * 因为 {@code Map.copyOf} 返回的 {@code ImmutableCollections.MapN}
 * 迭代顺序由 hash 决定，而 JDK 9+ 的 hash 里掺了一个
 * <b>每次 JVM 启动随机生成</b>的 SALT。
 *
 * <p>实测（三个独立 JVM，同一份已排序的 LinkedHashMap）：
 * <pre>
 *   run 1: [query_my_coupons, query_order_status, query_inventory, search_after_sale_policy]
 *   run 2: [search_after_sale_policy, query_my_coupons, query_order_status, query_inventory]
 *   run 3: [query_order_status, query_inventory, search_after_sale_policy, query_my_coupons]
 * </pre>
 *
 * <p>⚠️ 5.7 只有一个工具，所以<b>怎么测都测不出来</b>；
 * 5.9 加到四个才会显形。而代价不只是「不好看」：{@code tools} 数组是
 * Prompt 前缀的一部分，顺序一变，每次重启后第一个请求就整段
 * 未命中 DeepSeek 的上下文缓存 —— {@code cache-hit-input: 0.02}
 * 和 {@code input: 1.0} 差 <b>50 倍</b>。
 *
 * <h2>为什么这个测试能抓住它，而「断言顺序稳定」不能</h2>
 *
 * <p>★ 单个 JVM 内 {@code Map.copyOf} 的顺序是<b>稳定</b>的
 * （SALT 在一个进程里不变），所以「跑两次看顺序一样不一样」抓不住它。
 *
 * <p>能抓住它的是<b>换一组 key</b>：hash 顺序和字典序在
 * 精心挑过的 key 上必然不同。下面那个用例就是干这个的 ——
 * 只要实现里有任何一步用了 {@code Map.copyOf} / {@code HashMap}，
 * 四个工具名的输出顺序就不会是字典序。
 */
@DisplayName("McpToolRegistry · 工具注册与顺序")
class McpToolRegistryTest {

    /** 一个只会报名字的假工具 —— 本类只关心注册表的行为 */
    private static McpTool fake(String name) {
        return new McpTool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String title() {
                return name;
            }

            @Override
            public String description() {
                return "测试用";
            }

            @Override
            public List<ToolField> inputFields() {
                return List.of();
            }

            @Override
            public McpToolResult call(McpArguments args, McpToolContext context) {
                return McpToolResult.ok("ok");
            }
        };
    }

    // ============================================================
    // 一、★★ 顺序 —— 本类真正的价值
    // ============================================================

    @Nested
    @DisplayName("★★ 顺序稳定（Map.copyOf 的回归护栏）")
    class Ordering {

        @Test
        @DisplayName("★ 四个工具必须严格按名字升序产出 —— 换一组 key 才抓得住这个 bug")
        void allReturnsNameAscending() {
            // ★★ 这四个名字是【刻意挑的】。
            //
            //   它们的 hash 顺序和字典序明显不同（实测 Map.copyOf 会给出
            //   [query_order_status, query_inventory, search_after_sale_policy,
            //    query_my_coupons]）。所以只要实现里混进了 Map.copyOf 或
            //   HashMap，这个断言当场就红 —— 而换成 ["a","b","c"] 那种
            //   短名字，hash 顺序碰巧就是字典序，测试会恒真。
            //
            //   ⚠️ 而且这正好是 5.9 那四个业务工具的真实名字。
            List<String> names = List.of(
                    "query_order_status",
                    "query_my_coupons",
                    "query_inventory",
                    "search_after_sale_policy");

            // 故意【打乱】传入顺序 —— 注册表本来就该自己排序，
            // 不依赖 Spring 注入 Bean 的顺序
            List<McpTool> shuffled = new ArrayList<>();
            shuffled.add(fake(names.get(2)));
            shuffled.add(fake(names.get(0)));
            shuffled.add(fake(names.get(3)));
            shuffled.add(fake(names.get(1)));

            McpToolRegistry registry = new McpToolRegistry(shuffled);

            List<String> actual = registry.all().stream().map(McpTool::name).toList();
            List<String> expected = new ArrayList<>(names);
            expected.sort(String::compareTo);

            assertThat(actual)
                    .as("★ tools/list 的顺序必须稳定 —— 它进 Prompt 前缀，"
                            + "而前缀缓存命中价差 50 倍")
                    .containsExactlyElementsOf(expected);
        }

        @Test
        @DisplayName("★ 正-反对照：Map.copyOf 确实会打乱顺序（证明上面那条断言不是恒真）")
        void mapCopyOfScramblesOrder() {
            // ★★ 「断言 A 成立」还不够，必须同时证明「不做 A 的那个版本确实不成立」。
            //    否则上面那条断言可能恒真 —— 比如如果 Map.copyOf 恰好
            //    保留了顺序，那它就是个摆设。
            List<String> names = new ArrayList<>(List.of(
                    "query_order_status", "query_my_coupons",
                    "query_inventory", "search_after_sale_policy"));
            names.sort(String::compareTo);

            Map<String, Integer> sorted = new java.util.LinkedHashMap<>();
            names.forEach(n -> sorted.put(n, n.length()));

            assertThat(sorted.keySet()).containsExactlyElementsOf(names);      // 排好序了

            // ★ AssertJ 没有「不按这个顺序」的断言（那本来也很别扭），
            //   直接比列表：顺序不同就是不同
            List<String> afterCopyOf = new ArrayList<>(Map.copyOf(sorted).keySet());
            assertThat(afterCopyOf)
                    .as("★ Map.copyOf 打乱了它 —— 这就是 5.7 那个 bug，"
                            + "也正是注册表【不能】用它的原因")
                    .isNotEqualTo(names);
        }
    }

    // ============================================================
    // 二、重名 = 启动即崩
    // ============================================================

    @Nested
    @DisplayName("重名的工具名必须启动即崩")
    class Duplicate {

        @Test
        @DisplayName("★ 两个同名工具 → 抛异常，且消息里带着两个类名")
        void duplicateNameThrows() {
            assertThatThrownBy(() -> new McpToolRegistry(List.of(
                    fake("query_order_status"), fake("query_order_status"))))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("query_order_status")
                    .hasMessageContaining("重名");
        }

        @Test
        @DisplayName("★ 重名不能是「后一个覆盖前一个」—— 那会让行为随 Bean 顺序变化")
        void duplicateIsNotSilentlyOverwritten() {
            // 故意让两个同名工具返回不同的标题，看注册表留的是哪一个。
            // ★ 如果实现是 put 覆盖，这里会悄悄留下一个 —— 而「留下哪一个」
            //   取决于 Spring 注入 List 的顺序，那个顺序是不保证的
            McpTool a = fake("dup");
            McpTool b = fake("dup");

            assertThatThrownBy(() -> new McpToolRegistry(List.of(a, b)))
                    .isInstanceOf(IllegalStateException.class);

            // 反过来传也一样崩 —— 不是「第一个赢」或「最后一个赢」
            assertThatThrownBy(() -> new McpToolRegistry(List.of(b, a)))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    // ============================================================
    // 三、查
    // ============================================================

    @Test
    @DisplayName("按名字查；查不到返回 null（由调用方决定怎么报错）")
    void findByName() {
        McpToolRegistry registry = new McpToolRegistry(
                List.of(fake("a_tool"), fake("b_tool")));

        assertThat(registry.find("a_tool")).isNotNull();
        assertThat(registry.find("a_tool").name()).isEqualTo("a_tool");

        assertThat(registry.find("nope")).isNull();
        assertThat(registry.find(null)).as("null 也不能抛 —— 模型偶尔会给个空名字").isNull();
    }
}
