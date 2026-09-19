package com.xbla.rag.seed;

import com.xbla.rag.entity.AppUser;
import com.xbla.rag.entity.Coupon;
import com.xbla.rag.entity.UserCoupon;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 用户券的种子数据生成（阶段 5.9）。纯单测 —— {@link SeedDataFactory} 不碰数据库。
 *
 * <h3>★★ 本类真正要证明的三件事</h3>
 *
 * <ol>
 *   <li><b>可复现。</b> 同一个种子跑两次必须产出<b>逐字段相同</b>的券。
 *       这看起来是废话，但它正是 5.9 加第二个 {@code Random} 流的原因 ——
 *       而那个 bug 的表现是「我这儿 3 张券、你那儿 2 张」，
 *       会被归因成「数据没同步」，实际上是我们自己的不确定性</li>
 *   <li><b>三条数据库约束在构造期就成立。</b> 它们真的会被数据库拒绝
 *       （{@code ck_user_coupon_used_at} / {@code uk_user_coupon}），
 *       而那时错误发生在 {@code saveBatch} 里，
 *       报的是「违反约束 xxx」—— 一个指向 SQL、不指向生成逻辑的错误</li>
 *   <li><b>「未使用」的券必须真的没过期。</b>
 *       ★ 这条是最容易漏的：模板的 {@code valid_to} 是<b>建库那天 + 60 天</b>，
 *       而补券发生在建库很久之后 —— 按模板算的话，
 *       所有「未使用」的券算出来都是过期的，
 *       于是工具报「你有 3 张可用券」，而那 3 张一张都用不了</li>
 * </ol>
 */
@DisplayName("SeedDataFactory · 用户券")
class UserCouponSeedTest {

    private static final int USER_COUNT = 30;

    private final SeedDataFactory factory = new SeedDataFactory();

    // ============================================================
    // 夹具
    // ============================================================

    private static List<AppUser> users(int count) {
        List<AppUser> list = new ArrayList<>(count);
        for (long i = 1; i <= count; i++) {
            AppUser u = new AppUser();
            u.setId(i);
            u.setUserNo(String.format("U%06d", i));
            list.add(u);
        }
        return list;
    }

    private static List<Coupon> coupons(int count) {
        List<Coupon> list = new ArrayList<>(count);
        for (long i = 1; i <= count; i++) {
            Coupon c = new Coupon();
            c.setId(i);
            c.setCouponNo(String.format("C-%03d", i));
            list.add(c);
        }
        return list;
    }

    /** 前 half 个用户有订单，其余没有 —— 用来验「没订单的人不会拿到已使用的券」 */
    private static Map<Long, List<Long>> ordersFor(int userCount) {
        Map<Long, List<Long>> map = new HashMap<>();
        for (long i = 1; i <= userCount / 2; i++) {
            map.put(i, List.of(1000L + i, 2000L + i));
        }
        return map;
    }

    private List<UserCoupon> generate() {
        return factory.createUserCoupons(users(USER_COUNT), coupons(8), ordersFor(USER_COUNT));
    }

    // ============================================================
    // 一、可复现
    // ============================================================

    @Nested
    @DisplayName("★ 可复现：同一份种子必须产出同一批券")
    class Reproducible {

        @Test
        @DisplayName("★ 两个独立的工厂实例 → 逐字段相同")
        void twoInstancesAgree() {
            List<UserCoupon> a = generate();
            List<UserCoupon> b = new SeedDataFactory()
                    .createUserCoupons(users(USER_COUNT), coupons(8), ordersFor(USER_COUNT));

            assertThat(a).hasSameSizeAs(b);
            for (int i = 0; i < a.size(); i++) {
                UserCoupon x = a.get(i);
                UserCoupon y = b.get(i);
                assertThat(y.getUserId()).isEqualTo(x.getUserId());
                assertThat(y.getCouponId()).isEqualTo(x.getCouponId());
                assertThat(y.getStatus()).isEqualTo(x.getStatus());
                assertThat(y.getOrderId()).isEqualTo(x.getOrderId());
            }
        }

        @Test
        @DisplayName("★★ 和「先跑其它方法」解耦 —— 用【独立的随机流】才做得到")
        void independentOfOtherCalls() {
            // ★ 模拟「全新建库」那条路径：在生成券之前先消费掉一批随机数
            SeedDataFactory fresh = new SeedDataFactory();
            fresh.createUsers(USER_COUNT);
            fresh.createProducts(50);
            List<UserCoupon> afterOtherCalls =
                    fresh.createUserCoupons(users(USER_COUNT), coupons(8), ordersFor(USER_COUNT));

            // ★ 模拟「只补券」那条路径：什么都不先跑
            List<UserCoupon> directly = generate();

            assertThat(afterOtherCalls)
                    .as("★★ 共用一条随机流的话，这里会得到【两套不同的券】—— "
                            + "而症状是「我这儿 3 张、你那儿 2 张」，"
                            + "会被归因成「数据没同步」")
                    .hasSameSizeAs(directly);
            assertThat(afterOtherCalls.stream().map(UserCoupon::getCouponId).toList())
                    .isEqualTo(directly.stream().map(UserCoupon::getCouponId).toList());
            assertThat(afterOtherCalls.stream().map(UserCoupon::getStatus).toList())
                    .isEqualTo(directly.stream().map(UserCoupon::getStatus).toList());
        }
    }

    // ============================================================
    // 二、★★ 数据库的三条约束
    // ============================================================

    @Nested
    @DisplayName("★★ 构造出来的数据必须能通过数据库的 CHECK 和 UNIQUE")
    class Constraints {

        @Test
        @DisplayName("★★ uk_user_coupon：同一用户不能重复领同一张券模板")
        void noDuplicatePerUser() {
            Set<String> seen = new HashSet<>();
            for (UserCoupon uc : generate()) {
                assertThat(seen.add(uc.getUserId() + ":" + uc.getCouponId()))
                        .as("★★ (user_id, coupon_id) 上有唯一约束 —— 重复了会在 saveBatch 里"
                                + "报「违反约束 uk_user_coupon」，一个指向 SQL、"
                                + "不指向生成逻辑的错误")
                        .isTrue();
            }
        }

        @Test
        @DisplayName("★★ ck_user_coupon_used_at：status=2 必须有 order_id 和 used_at")
        void usedCouponIsComplete() {
            List<UserCoupon> all = generate();

            assertThat(all).anyMatch(uc -> uc.getStatus() == 2);

            assertThat(all)
                    .filteredOn(uc -> uc.getStatus() == 2)
                    .allSatisfy(uc -> {
                        assertThat(uc.getOrderId()).as("已使用却没有订单").isNotNull();
                        assertThat(uc.getUsedAt()).as("已使用却没有使用时间").isNotNull();
                    });
        }

        @Test
        @DisplayName("★★ ck_user_coupon_used_at 的另一半：status ≠ 2 时 used_at 必须是 null")
        void unusedCouponHasNoUsedAt() {
            assertThat(generate())
                    .filteredOn(uc -> uc.getStatus() != 2)
                    .allSatisfy(uc -> assertThat(uc.getUsedAt())
                            .as("★★ 数据库的 CHECK 写的是 "
                                    + "「status = 2 AND used_at IS NOT NULL ... OR status <> 2 AND used_at IS NULL」"
                                    + " —— 漏了这一半，整批 INSERT 会被一起拒绝")
                            .isNull());
        }

        @Test
        @DisplayName("★ status 只能是 1 / 2 / 3")
        void statusInRange() {
            assertThat(generate())
                    .allSatisfy(uc -> assertThat(uc.getStatus()).isIn(1, 2, 3));
        }

        @Test
        @DisplayName("★ 没有订单的用户拿不到「已使用」的券")
        void usersWithoutOrdersGetNoUsedCoupons() {
            Map<Long, List<Long>> orders = ordersFor(USER_COUNT);
            List<UserCoupon> all = factory.createUserCoupons(
                    users(USER_COUNT), coupons(8), orders);

            assertThat(all)
                    .filteredOn(uc -> !orders.containsKey(uc.getUserId()))
                    .as("★ status=2 需要 order_id，而它是 NOT NULL —— "
                            + "给没订单的人发「已使用」的券会在 INSERT 时炸")
                    .allSatisfy(uc -> assertThat(uc.getStatus()).isEqualTo(1));
        }
    }

    // ============================================================
    // 三、★★ 时间线的自洽
    // ============================================================

    @Nested
    @DisplayName("★★ 三条时间线必须自洽 —— 尤其是「未使用」必须真的没过期")
    class Timeline {

        @Test
        @DisplayName("★★ 所有「未使用」的券，expired_at 必须在【未来】")
        void unusedCouponsAreNotExpired() {
            OffsetDateTime now = OffsetDateTime.now();

            assertThat(generate())
                    .filteredOn(uc -> uc.getStatus() == 1)
                    .as("★ 用例的前提是确实存在未使用的券")
                    .isNotEmpty()
                    .allSatisfy(uc -> assertThat(uc.getExpiredAt())
                            .as("★★ 按券模板的 valid_to 算的话，补券时会【全部落在过去】—— "
                                    + "而模板的 valid_to 是「建库那天 + 60 天」。"
                                    + "后果是工具报「你有 3 张可用券」，而那 3 张一张都用不了")
                            .isAfter(now));
        }

        @Test
        @DisplayName("★★ 所有「已过期」的券，expired_at 必须在【过去】")
        void expiredCouponsAreExpired() {
            OffsetDateTime now = OffsetDateTime.now();

            assertThat(generate())
                    .filteredOn(uc -> uc.getStatus() == 3)
                    .as("★ 前提是确实存在已过期的券")
                    .isNotEmpty()
                    .allSatisfy(uc -> assertThat(uc.getExpiredAt()).isBefore(now));
        }

        @Test
        @DisplayName("★ received_at 不能晚于 expired_at（否则是「先过期后领券」）")
        void receivedBeforeExpired() {
            assertThat(generate())
                    .allSatisfy(uc -> assertThat(uc.getReceivedAt())
                            .as("★ 数据库拦不住这条（没有这个 CHECK），"
                                    + "而工具渲染出来是「你在 8 月 20 日领了一张 8 月 1 日过期的券」")
                            .isBefore(uc.getExpiredAt()));
        }

        @Test
        @DisplayName("★ 已使用的券：领券 → 使用，两段必须递增")
        void usedTimelineOrdered() {
            assertThat(generate())
                    .filteredOn(uc -> uc.getStatus() == 2)
                    .allSatisfy(uc -> {
                        assertThat(uc.getUsedAt()).isAfter(uc.getReceivedAt());
                        assertThat(uc.getExpiredAt()).isAfter(uc.getUsedAt());
                    });
        }
    }

    // ============================================================
    // 四、覆盖度
    // ============================================================

    @Test
    @DisplayName("★ 每个用户都有券（否则「我的优惠券」在多数账号上都是空的）")
    void everyUserGetsAtLeastOne() {
        Map<Long, Integer> perUser = new HashMap<>();
        for (UserCoupon uc : generate()) {
            perUser.merge(uc.getUserId(), 1, Integer::sum);
        }

        assertThat(perUser).hasSize(USER_COUNT);
        assertThat(perUser.values()).allSatisfy(n -> assertThat(n).isBetween(1, 4));
    }

    @Test
    @DisplayName("★ 三种状态都要出现 —— 否则「我的券怎么用不了」这类问题测不出来")
    void allThreeStatusesAppear() {
        assertThat(generate().stream().map(UserCoupon::getStatus).distinct().sorted().toList())
                .containsExactly(1, 2, 3);
    }
}
