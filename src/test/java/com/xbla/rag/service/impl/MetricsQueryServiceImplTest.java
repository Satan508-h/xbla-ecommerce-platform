package com.xbla.rag.service.impl;

import com.xbla.rag.dto.LabeledCount;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

/**
 * 「固定键集」那个纯函数（阶段 9.6b）—— <b>值没出现的键，必须补成 0 并保留</b>。
 *
 * <h2>★★★ 为什么这件事要单独拿出来测，而不是在 HTTP 层断言</h2>
 *
 * <p>通过端点断言「值为 0 的键也在」是<b>可能恒真</b>的：只有在
 * 「那个窗口里<b>恰好</b>缺这一个键」时才失效 —— 而那是<b>数据依赖</b>的，
 * 今天红、明天绿，取决于当时库里有什么。
 *
 * <p>★ 而固定的键集是个<b>纯函数</b>。直接喂一组我控制得了的输入，
 * 「缺的那个补成 0」才是真的被断言了。
 * 同本项目那条纪律：<b>纯单测必须能构造出「不做这件事的那个版本确实不成立」</b>。
 *
 * <h2>★ 这个函数守的是 9.6a 那一次的教训</h2>
 *
 * <p>{@code d2386cd}：六个 gate 分支<b>全填 0 再计数</b>，因为
 * 「这一支没被走到」和「这一支不存在」是两件事，而<b>缺键会把前者渲染成后者</b>
 * —— 一个看起来像好消息的空。
 */
@DisplayName("MetricsQueryServiceImpl · 固定键集")
class MetricsQueryServiceImplTest {

    private static final List<String> KEYS = List.of("1", "2", "3", "4");

    @Test
    @DisplayName("★★ 查询结果里【没有】的键 → 补成 0，而不是消失")
    void missingKeysBecomeZero() {
        // 只有 1 和 3 有数据 —— 2 和 4 在 GROUP BY 里根本不会出现
        Map<String, Long> out = MetricsQueryServiceImpl.fixedKeysWithCounts(
                KEYS, List.of(new LabeledCount("1", 20), new LabeledCount("3", 12)));

        assertThat(out).as("★★ 少一个键就是「这一支不存在」，而它其实只是「没被走到」")
                .containsExactly(
                        entry("1", 20L),
                        entry("2", 0L),
                        entry("3", 12L),
                        entry("4", 0L));
    }

    @Test
    @DisplayName("★★ 反：全都缺 → 四个键仍然全在（不是空 map）")
    void emptyResultStillHasAllKeys() {
        Map<String, Long> out = MetricsQueryServiceImpl.fixedKeysWithCounts(KEYS, List.of());

        // ★ 少了这一条，上面那条在「实现直接 return 空 map」时也可能压根跑不到
        assertThat(out).containsOnlyKeys("1", "2", "3", "4");
        assertThat(out.values()).containsOnly(0L);
    }

    @Test
    @DisplayName("★ 查出来一个【不在固定集里】的键 → 追加，不丢弃")
    void unexpectedKeysAreAppended() {
        Map<String, Long> out = MetricsQueryServiceImpl.fixedKeysWithCounts(
                KEYS, List.of(new LabeledCount("1", 5), new LabeledCount("9", 2)));

        // ★ 丢弃它是这个类里最坏的一种「让数据好看」：
        //   status=9 意味着取值超纲了，而那【必须被看见】
        assertThat(out).containsKey("9");
        assertThat(out.get("9")).isEqualTo(2L);
        assertThat(out).containsOnlyKeys("1", "2", "3", "4", "9");
    }

    @Test
    @DisplayName("★ 标签是 null 的那一组也不丢 —— 它会以 \"null\" 出现")
    void nullLabelIsKept() {
        Map<String, Long> out = MetricsQueryServiceImpl.fixedKeysWithCounts(
                KEYS, List.of(new LabeledCount(null, 3)));

        // ★ feedback 的 payload 读不出 vote 时会走到这一格。
        //   丢掉它，就会让「票数加起来对不上」变成一个无法解释的差
        assertThat(out).containsKey("null");
    }

    @Test
    @DisplayName("★★ 键序钉死：固定键在前、超纲键在后 —— 两次调用逐字节相同")
    void keyOrderIsStable() {
        Map<String, Long> out = MetricsQueryServiceImpl.fixedKeysWithCounts(
                KEYS, List.of(new LabeledCount("9", 1), new LabeledCount("1", 2)));

        assertThat(out.keySet()).containsExactly("1", "2", "3", "4", "9");
    }

    @Test
    @DisplayName("★ 同一个键出现两次 → 相加，不覆盖")
    void duplicateLabelsAreSummed() {
        Map<String, Long> out = MetricsQueryServiceImpl.fixedKeysWithCounts(
                KEYS, List.of(new LabeledCount("1", 2), new LabeledCount("1", 3)));

        assertThat(out.get("1")).as("覆盖会让总数凭空少一截，而且看不出来").isEqualTo(5L);
    }
}
