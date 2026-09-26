package com.xbla.rag.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.xbla.rag.dto.UserEventRequest;
import com.xbla.rag.entity.UserEvent;
import com.xbla.rag.mapper.UserEventMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 埋点上报（阶段 9.6b）—— <b>「收什么、丢什么、以及丢的时候不出声」</b>。
 *
 * <h2>★★★ 这个类守的第一件事：五条校验，每条都对应一个静默失败</h2>
 *
 * <pre>
 *   类型不在白名单   → 库里的 event_type 变自由文本，
 *                      而指标按固定键集分组 ⇒ 那些行【永远不出现】
 *   缺幂等键         → ON CONFLICT 失去意义 ⇒ 重复计数
 *   缺 occurred_at   → 那一列变 NULL
 *   字段超长         → PG 抛 value too long ⇒ 500
 *   payload 过大     → 截断会造出非法 JSON ⇒ PG 拒 ⇒ 500
 * </pre>
 *
 * <p>★ 它们<b>全部不会在开发时暴露</b>（本地发的都是正常数据），
 * 而症状全都长得像「用户没点」。
 *
 * <h2>★★★ 第二件事：幂等【真的】是靠数据库约束做到的</h2>
 *
 * <p>「同一个 eventNo 写两次只有一行」这件事，如果只靠服务端先查后写，
 * 在并发下就是错的。所以这里做的是一条 <b>ON CONFLICT DO NOTHING</b>，
 * 而用例断言的是<b>两个不同的返回值</b>（1 与 0）——
 * 只断言「表里有一行」的话，「两次都插了然后其中一行被别的东西删了」
 * 也能通过。
 *
 * <h2>★★ 第三件事：{@code received_at} 由数据库默认值填</h2>
 *
 * <p>装配层刻意不在 Java 里设这一列（「一个事实不要两个时钟」）。
 * 而这条依赖<b>能静默失效</b>：一旦有人给实体加上 {@code @TableField(fill = INSERT)}
 * 或者改了 MyBatis-Plus 的字段策略，它就会变成一次「列不可为空」的插入失败 ——
 * 或者更坏：某天那个默认值被去掉了，于是<b>所有埋点的时间都变成 null</b>。
 * ⇒ 用例直接断言插入后它非空。
 */
@SpringBootTest
@Transactional
@DisplayName("UserEventService · 埋点上报")
class UserEventServiceIntegrationTest {

    @Autowired
    private UserEventService userEventService;

    @Autowired
    private UserEventMapper userEventMapper;

    // ============================================================
    // 夹具
    // ============================================================

    private static String unique(String prefix) {
        return prefix + "-" + System.nanoTime();
    }

    /** 一条合法的 ref_click */
    private UserEventRequest validClick(String eventNo) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("chunkId", 348);
        payload.put("no", 2);
        return new UserEventRequest(eventNo, "ref_click", 8L,
                unique("SESS"), unique("TRACE"), payload, OffsetDateTime.now());
    }

    private UserEvent find(String eventNo) {
        return userEventMapper.selectOne(
                Wrappers.<UserEvent>lambdaQuery().eq(UserEvent::getEventNo, eventNo));
    }

    private long count(String eventNo) {
        return userEventMapper.selectCount(
                Wrappers.<UserEvent>lambdaQuery().eq(UserEvent::getEventNo, eventNo));
    }

    // ============================================================
    // 一、正常路径
    // ============================================================

    @Nested
    @DisplayName("一、正常路径")
    class Happy {

        @Test
        @DisplayName("合法事件 → WRITTEN，而且字段逐个落对")
        void writesValidEvent() {
            String no = unique("EV");
            UserEventService.Outcome outcome = userEventService.record(validClick(no));

            assertThat(outcome).isEqualTo(UserEventService.Outcome.WRITTEN);

            UserEvent row = find(no);
            assertThat(row).as("写进去了就该查得到").isNotNull();
            assertThat(row.getEventType()).isEqualTo("ref_click");
            assertThat(row.getUserId()).isEqualTo(8L);
            assertThat(row.getPayload()).contains("348").contains("chunkId");
        }

        /**
         * ★★★ 这条钉的是「{@code received_at} 由数据库默认值填」这个依赖。
         *
         * <p>它<b>看不见的失效方式</b>：有人给实体加了自动填充、
         * 或者改了字段策略、或者那个 {@code DEFAULT now()} 被去掉了 ——
         * 三种都会让这一列变成 null，而<b>没有任何别的用例会红</b>。
         */
        @Test
        @DisplayName("★★ received_at 由数据库默认值填 —— 非空，且不早于 occurredAt")
        void receivedAtComesFromDatabaseDefault() {
            String no = unique("EV");
            // ⚠️ 必须截到【微秒】—— PG 的 timestamptz 只到微秒，Java 的 now() 到纳秒，
            //    多出来的那几位会被 PG 四舍五入（实测 ...097600 → ...098），
            //    于是「同一个瞬间」在 equals 下判 false。★ 这不是 flaky，是精度差
            OffsetDateTime occurred = OffsetDateTime.now()
                    .minusMinutes(5).truncatedTo(ChronoUnit.MICROS);
            userEventService.record(new UserEventRequest(
                    no, "feedback", 8L, null, null, Map.of("vote", "up"), occurred));

            UserEvent row = find(no);
            assertThat(row.getReceivedAt())
                    .as("★ 装配层不设这一列，它必须由 DEFAULT now() 填上")
                    .isNotNull();

            // ⚠️ 比的是【时刻】不是 OffsetDateTime —— 后者连时区偏移一起比，
            //    而 PG 回的是 UTC、我们传的是 +08:00：
            //    同一个瞬间，`equals` 判 false（实测栽过一次）
            assertThat(row.getOccurredAt().toInstant()).isEqualTo(occurred.toInstant());
            // ★ 两列【不是同一个值】—— 这正是 V18 第四节要两列的理由
            assertThat(row.getReceivedAt().toInstant()).isAfter(row.getOccurredAt().toInstant());
        }

        @Test
        @DisplayName("可选字段缺席也能写：userId / sessionNo / traceId / payload 都可空")
        void optionalFieldsMayBeAbsent() {
            String no = unique("EV");
            UserEventService.Outcome outcome = userEventService.record(new UserEventRequest(
                    no, "ref_click", null, null, null, null, OffsetDateTime.now()));

            assertThat(outcome).isEqualTo(UserEventService.Outcome.WRITTEN);
            UserEvent row = find(no);
            assertThat(row.getUserId()).isNull();
            // ★ payload 缺席 → SQL NULL，不是 "{}"（同全库「没发生不留空对象」那条）
            assertThat(row.getPayload()).isNull();
        }
    }

    // ============================================================
    // 二、★★★ 幂等
    // ============================================================

    @Nested
    @DisplayName("二、★★★ 幂等")
    class Idempotent {

        @Test
        @DisplayName("★★ 同一个 eventNo 写两次 → 第二次 DUPLICATE，且表里只有一行")
        void secondWriteIsDuplicate() {
            String no = unique("EV");
            UserEventRequest req = validClick(no);

            assertThat(userEventService.record(req)).isEqualTo(UserEventService.Outcome.WRITTEN);
            assertThat(userEventService.record(req)).isEqualTo(UserEventService.Outcome.DUPLICATE);

            assertThat(count(no)).as("两次投递，一行数据").isEqualTo(1);
        }

        /**
         * ★★ 反面：{@code eventNo} <b>不同</b>时两次都要写进去。
         *
         * <p>少了这一条，「什么都返回 DUPLICATE」的实现也能让上面那条通过 ——
         * 而那等于埋点全丢。
         */
        @Test
        @DisplayName("★★ 反：eventNo 不同 → 两次都是 WRITTEN（不是「什么都算重复」）")
        void differentKeysBothWrite() {
            assertThat(userEventService.record(validClick(unique("EV"))))
                    .isEqualTo(UserEventService.Outcome.WRITTEN);
            assertThat(userEventService.record(validClick(unique("EV"))))
                    .isEqualTo(UserEventService.Outcome.WRITTEN);
        }

        @Test
        @DisplayName("★ 幂等的判据是 eventNo，不是「内容相同」—— 同样内容换个键就是两条")
        void sameContentDifferentKeyAreTwoRows() {
            UserEventRequest a = validClick(unique("EV"));
            // 内容逐字相同，只有 eventNo 不同
            UserEventRequest b = new UserEventRequest(unique("EV"), a.eventType(), a.userId(),
                    a.sessionNo(), a.traceId(), a.payload(), a.occurredAt());

            assertThat(userEventService.record(a)).isEqualTo(UserEventService.Outcome.WRITTEN);
            assertThat(userEventService.record(b)).isEqualTo(UserEventService.Outcome.WRITTEN);
        }
    }

    // ============================================================
    // 三、校验：每条都丢弃，且【都不抛异常】
    // ============================================================

    @Nested
    @DisplayName("三、校验：丢弃而不是抛异常")
    class Discards {

        private void assertDiscarded(UserEventRequest req, String why) {
            // ★ 断言的是「不抛」+「不进库」两件事。
            //   assertThatCode 而不是 assertThatThrownBy —— 这里要的是【没有异常】
            assertThat(userEventService.record(req)).as(why)
                    .isEqualTo(UserEventService.Outcome.DISCARDED);
        }

        @Test
        @DisplayName("★ 类型不在白名单 → 丢弃（不是存成自由文本）")
        void unknownTypeIsDiscarded() {
            assertDiscarded(new UserEventRequest(unique("EV"), "product_click", 8L,
                    null, null, null, OffsetDateTime.now()), "白名单外");
            assertDiscarded(new UserEventRequest(unique("EV"), null, 8L,
                    null, null, null, OffsetDateTime.now()), "类型为空");
        }

        @Test
        @DisplayName("★ 缺幂等键 → 丢弃（没有它就没法防重）")
        void missingEventNoIsDiscarded() {
            assertDiscarded(new UserEventRequest(null, "ref_click", 8L,
                    null, null, null, OffsetDateTime.now()), "null");
            assertDiscarded(new UserEventRequest("   ", "ref_click", 8L,
                    null, null, null, OffsetDateTime.now()), "全是空白");
        }

        @Test
        @DisplayName("★★ 缺 occurredAt → 丢弃。★ 服务端【不】代填 now()")
        void missingOccurredAtIsDiscarded() {
            assertDiscarded(new UserEventRequest(unique("EV"), "ref_click", 8L,
                    null, null, null, null),
                    "代填 now() 会毁掉 occurred_at 与 received_at 的区别（V18 第四节）");
        }

        @Test
        @DisplayName("★ 标识列超长 → 丢弃（截断可能撞上另一条记录）")
        void overlongIdIsDiscarded() {
            String tooLong = "x".repeat(UserEventRequest.MAX_ID_LENGTH + 1);
            assertDiscarded(new UserEventRequest(tooLong, "ref_click", 8L,
                    null, null, null, OffsetDateTime.now()), "eventNo 超长");
            assertDiscarded(new UserEventRequest(unique("EV"), "ref_click", 8L,
                    null, tooLong, null, OffsetDateTime.now()), "traceId 超长");
        }

        /**
         * ★★ payload 超限必须<b>整条丢弃</b>，不是截断。
         *
         * <p>截断一个 JSON 会造出非法 JSONB，而 PG 会在写入时直接拒 ——
         * 于是「体积防护」自己变成了一次 500。
         */
        @Test
        @DisplayName("★★ payload 超限 → 整条丢弃（截断会造出非法 JSON ⇒ 500）")
        void oversizedPayloadIsDiscarded() {
            Map<String, Object> big = new HashMap<>();
            big.put("junk", "x".repeat(UserEventRequest.MAX_PAYLOAD_BYTES + 100));
            assertDiscarded(new UserEventRequest(unique("EV"), "ref_click", 8L,
                    null, null, big, OffsetDateTime.now()), "体积超限");
        }

        @Test
        @DisplayName("★ 请求体整个是 null → 丢弃，不抛 NullPointerException")
        void nullRequestIsDiscarded() {
            assertDiscarded(null, "空请求体");
        }

        @Test
        @DisplayName("★ 边界：刚好等于上限的字段【要收】—— 证明上面那些不是「什么都丢」")
        void exactlyAtLimitIsAccepted() {
            String atLimit = "y".repeat(UserEventRequest.MAX_ID_LENGTH);
            assertThat(userEventService.record(new UserEventRequest(atLimit, "ref_click", 8L,
                    null, null, null, OffsetDateTime.now())))
                    .as("== 上限收，> 上限丢；差一个字符的两边都要有")
                    .isEqualTo(UserEventService.Outcome.WRITTEN);
        }
    }
}
