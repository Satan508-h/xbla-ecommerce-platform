package com.xbla.rag.seed;

import com.xbla.rag.entity.AfterSalePolicy;
import com.xbla.rag.entity.AppUser;
import com.xbla.rag.entity.Coupon;
import com.xbla.rag.entity.Inventory;
import com.xbla.rag.entity.OrderItem;
import com.xbla.rag.entity.Orders;
import com.xbla.rag.entity.Product;
import com.xbla.rag.entity.ProductAttribute;
import com.xbla.rag.entity.ProductSku;
import com.xbla.rag.entity.UserCoupon;
import com.xbla.rag.service.AfterSalePolicyService;
import com.xbla.rag.service.AppUserService;
import com.xbla.rag.service.CouponService;
import com.xbla.rag.service.InventoryService;
import com.xbla.rag.service.OrderItemService;
import com.xbla.rag.service.OrdersService;
import com.xbla.rag.service.ProductAttributeService;
import com.xbla.rag.service.ProductService;
import com.xbla.rag.service.ProductSkuService;
import com.xbla.rag.service.UserCouponService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;

/**
 * 种子数据灌入器。
 *
 * <p><b>怎么运行</b>：
 * <pre>
 * ./mvnw spring-boot:run -Dspring-boot.run.profiles=local,seed
 * </pre>
 * IDE 里则是给 Run Configuration 加 VM 参数 {@code -Dspring.profiles.active=local,seed}。
 *
 * <p>⚠️ <b>必须写成 {@code local,seed} 两个，不能只写 {@code seed}。</b>
 * Spring Boot 的 {@code spring.profiles.active} 是<b>覆盖</b>语义而不是追加——
 * 命令行传了 {@code seed}，application.yml 里配的 {@code active: local} 就被顶掉了，
 * application-local.yml 不会加载，数据库密码丢失，启动时报：
 * <pre>
 * The server requested SCRAM-based authentication, but no password was provided.
 * </pre>
 *
 * <p><b>为什么用 @Profile("seed") 而不是每次都跑？</b>
 * 灌种子数据是<b>一次性</b>操作，不是应用启动的常规流程。
 * 放在独立 profile 里，日常启动（不带 seed）就完全不会触发它。
 *
 * <h2>★★ 幂等粒度是「按表」，不是「按整个库」</h2>
 *
 * <p>5.9 之前这里开头是一个总开关：{@code product} 表非空就 {@code return}，
 * 于是<b>什么都补不了</b>。而 5.9 恰好需要「补一张表、别动其它表」——
 * {@code user_coupon} 有 0 行，但重灌商品会连带把 208 份文档、
 * 1652 条切片和它们的向量全部作废（{@code kb_chunk.related_product_id}
 * 指向 {@code product.id}）。
 *
 * <p>所以改成<b>每一步自己判断要不要跑</b>。这条规则要成立，需要配套一条纪律：
 *
 * <blockquote>
 *   <b>每一步需要什么，就<b>从库里读</b>什么；不要靠「前面的步骤在同一进程里
 *   刚创建的那些对象」。</b>
 * </blockquote>
 *
 * <p>否则「跳过第 2 步」会顺手把第 3 步也弄坏 —— 它拿不到第 2 步本该传下来的东西。
 * 按这条纪律写之后，<b>「整个库是空的」只是「每张表都空」的一个特例</b>，
 * 两条路径走的是同一份代码，而不是两条。
 *
 * <p>⚠️ 依赖顺序仍然不能乱（外键要求被引用的表先有数据）：
 * <pre>
 *   app_user → coupon → product → product_sku → inventory
 *                                → product_attribute
 *                     → orders → order_item → user_coupon
 * </pre>
 *
 * <p><b>为什么不用 @Transactional 包起来？</b>
 * 灌 3000+ 条数据如果中途失败，包在事务里会全部回滚，
 * 但你看不到「卡在哪一步」。不包事务的话，失败点之前的进度是可见的，
 * 排查更直观——而且<b>每一步各自的幂等检查</b>能防止重复灌入。
 */
@Slf4j
@Component
@Profile("seed")
@RequiredArgsConstructor
public class SeedRunner implements CommandLineRunner {

    /** 商品总数。路线图 1.10 要求「约 200 商品」 */
    private static final int PRODUCT_COUNT = 200;
    private static final int USER_COUNT = 30;
    private static final int ORDER_COUNT = 120;

    private final ProductService productService;
    private final ProductSkuService productSkuService;
    private final ProductAttributeService productAttributeService;
    private final InventoryService inventoryService;
    private final AppUserService appUserService;
    private final OrdersService ordersService;
    private final OrderItemService orderItemService;
    private final CouponService couponService;
    private final UserCouponService userCouponService;
    private final AfterSalePolicyService afterSalePolicyService;

    private final SeedDataFactory factory = new SeedDataFactory();
    private final Random random = new Random(20260918L);

    @Override
    public void run(String... args) {
        long start = System.currentTimeMillis();
        log.info("=== 开始检查并灌入种子数据（按表幂等）===");

        seedUsers();
        seedProducts();
        seedCouponsAndOrders();
        seedAfterSalePolicies();
        seedUserCoupons();          // ⑩ 阶段 5.9 新增

        log.info("=== 种子数据检查/灌入完成，耗时 {} ms ===", System.currentTimeMillis() - start);
        printSummary();
    }

    // ============================================================
    // ① 用户
    // ============================================================

    private void seedUsers() {
        if (skipIfPresent("① 用户", appUserService.count())) {
            return;
        }
        List<AppUser> users = factory.createUsers(USER_COUNT);
        // 逐个 save 而不是 saveBatch：后面生成订单时要用到用户的自增 id，
        // 逐个插入能确保 id 被立即回填到对象上。
        users.forEach(appUserService::save);
        log.info("① 用户: {} 条", users.size());
    }

    // ============================================================
    // ② 商品 → ③ SKU → ④ 参数 → ⑤ 库存
    // ============================================================

    /** 商品 + 它的 SKU 列表 */
    private record ProductBundleRef(Product product, List<ProductSku> skus) {}

    private void seedProducts() {
        if (skipIfPresent("② 商品", productService.count())) {
            return;
        }
        List<SeedDataFactory.ProductBundle> bundles = factory.createProducts(PRODUCT_COUNT);

        List<ProductAttribute> allAttributes = new ArrayList<>();
        List<Inventory> allInventories = new ArrayList<>();
        int skuTotal = 0;

        for (SeedDataFactory.ProductBundle bundle : bundles) {
            Product product = bundle.product();

            // ② 插入商品，拿到自增 id
            productService.save(product);

            // ③ SKU：回填 productId 后插入，再拿到 skuId 建库存
            for (ProductSku sku : bundle.skus()) {
                sku.setProductId(product.getId());
                productSkuService.save(sku);
                allInventories.add(factory.createInventory(sku.getId()));
                skuTotal++;
            }

            // ④ 商品参数：回填 productId
            List<ProductAttribute> attrs = bundle.attributes();
            attrs.forEach(a -> a.setProductId(product.getId()));
            allAttributes.addAll(attrs);
        }

        // ④ 和 ⑤ 是叶子节点，不需要回填 id，可以用批量插入
        productAttributeService.saveBatch(allAttributes);
        inventoryService.saveBatch(allInventories);

        log.info("② 商品: {} 条", bundles.size());
        log.info("③ SKU: {} 条", skuTotal);
        log.info("④ 商品参数: {} 条", allAttributes.size());
        log.info("⑤ 库存: {} 条", allInventories.size());
    }

    // ============================================================
    // ⑥ 优惠券 → ⑦ 订单 → ⑧ 订单明细
    // ============================================================

    private void seedCouponsAndOrders() {
        seedCouponTemplates();

        if (skipIfPresent("⑦ 订单", ordersService.count())) {
            return;
        }
        // ★ 见类注释：需要什么就从库里读什么，不靠参数传递。
        //   这样「商品表已有数据、订单表是空的」也能正确补上
        List<AppUser> users = appUserService.list();
        List<ProductBundleRef> products = loadProductRefs();
        if (users.isEmpty() || products.isEmpty()) {
            log.warn("⑦ 跳过订单：app_user={} 条，有 SKU 的商品={} 条 —— 两者都必须先有数据",
                    users.size(), products.size());
            return;
        }

        List<Orders> orders = new ArrayList<>(ORDER_COUNT);
        List<OrderItem> orderItems = new ArrayList<>();

        for (int i = 1; i <= ORDER_COUNT; i++) {
            AppUser user = users.get(random.nextInt(users.size()));
            ProductBundleRef ref = products.get(random.nextInt(products.size()));
            Product product = ref.product();
            ProductSku sku = ref.skus().get(random.nextInt(ref.skus().size()));

            int quantity = 1 + random.nextInt(3);
            BigDecimal subtotal = sku.getPrice().multiply(BigDecimal.valueOf(quantity));
            BigDecimal discount = random.nextInt(3) == 0
                    ? subtotal.multiply(BigDecimal.valueOf(0.05 + random.nextInt(11) / 100.0))
                        .setScale(2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
            BigDecimal payAmount = subtotal.subtract(discount).max(BigDecimal.ZERO);

            // 状态按真实分布：已完成的订单占多数
            int status = switch (random.nextInt(10)) {
                case 0 -> 10;   // 待付款
                case 1, 2 -> 20; // 待发货
                case 3 -> 30;   // 已发货
                case 4 -> 50;   // 已取消
                default -> 40;  // 已完成
            };

            OffsetDateTime createdAt = OffsetDateTime.now()
                    .minusDays(random.nextInt(90))
                    .minusHours(random.nextInt(24));

            Orders order = new Orders();
            order.setOrderNo(String.format("SO%s%04d",
                    createdAt.toLocalDate().toString().replace("-", ""), i));
            order.setUserId(user.getId());
            order.setTotalAmount(subtotal);
            order.setDiscountAmount(discount);
            order.setPayAmount(payAmount);
            order.setStatus(status);
            order.setReceiverName(user.getNickname());
            order.setReceiverPhone(user.getPhone());
            order.setReceiverAddress(randomSubset(List.of(
                    "上海市浦东新区张江路 100 号",
                    "北京市海淀区中关村大街 1 号",
                    "广州市天河区天河路 200 号",
                    "成都市高新区天府大道 300 号",
                    "杭州市西湖区文三路 400 号")));
            order.setCreatedAt(createdAt);
            order.setUpdatedAt(createdAt);

            // 按状态回填各时间点，保证时间线自洽
            if (status >= 20) order.setPaidAt(createdAt.plusMinutes(5));
            if (status >= 30) order.setShippedAt(createdAt.plusDays(1));
            if (status == 40) order.setCompletedAt(createdAt.plusDays(3));
            if (status == 50) order.setCancelledAt(createdAt.plusHours(2));
            if (status >= 30) {
                order.setLogisticsCompany(randomSubset(List.of("顺丰速运", "京东物流", "中通快递", "圆通速递")));
                order.setLogisticsNo("SF" + String.format("%012d", random.nextInt(1_000_000)));
            }

            ordersService.save(order);

            // ⑧ 订单明细：★ 注意这里存的是快照（商品名/规格/单价）
            OrderItem item = new OrderItem();
            item.setOrderId(order.getId());
            item.setProductId(product.getId());
            item.setSkuId(sku.getId());
            item.setProductName(product.getName());   // 快照
            item.setSpecName(sku.getSpecName());      // 快照
            item.setPrice(sku.getPrice());            // 快照
            item.setQuantity(quantity);
            item.setSubtotal(subtotal);
            orderItems.add(item);
        }

        orderItemService.saveBatch(orderItems);
        log.info("⑦ 订单: {} 条", orders.size());
        log.info("⑧ 订单明细: {} 条", orderItems.size());
    }

    /** ⑥ 券模板。它和订单各自独立判断，见类注释 */
    private void seedCouponTemplates() {
        if (skipIfPresent("⑥ 优惠券模板", couponService.count())) {
            return;
        }
        List<Coupon> coupons = factory.createCoupons();
        coupons.forEach(couponService::save);
        log.info("⑥ 优惠券模板: {} 条", coupons.size());
    }

    /**
     * 从库里读回「商品 + 它的 SKU」。
     *
     * <p>★ 只取 {@code id} 和 {@code name}，不 select 整行 —— {@code Product}
     * 带着 {@code description} / {@code selling_points} 这些长文本，
     * 而这个方法只需要「给我一个 id 和一个名字」。
     *
     * <p>⚠️ <b>没有 SKU 的商品会被过滤掉。</b> 留着它的话，下面那句
     * {@code ref.skus().get(random.nextInt(...))} 会抛
     * {@code IndexOutOfBoundsException} —— 一个指向随机数、和真实原因
     * （商品没有 SKU）毫无关系的异常。
     */
    private List<ProductBundleRef> loadProductRefs() {
        List<Product> products = productService.lambdaQuery()
                .select(Product::getId, Product::getName)
                .list();
        Map<Long, List<ProductSku>> skusByProduct = productSkuService.list().stream()
                .collect(Collectors.groupingBy(ProductSku::getProductId));

        List<ProductBundleRef> refs = new ArrayList<>(products.size());
        for (Product product : products) {
            List<ProductSku> skus = skusByProduct.getOrDefault(product.getId(), List.of());
            if (!skus.isEmpty()) {
                refs.add(new ProductBundleRef(product, skus));
            }
        }
        return refs;
    }

    // ============================================================
    // ⑨ 售后政策
    // ============================================================

    private void seedAfterSalePolicies() {
        if (skipIfPresent("⑨ 售后政策", afterSalePolicyService.count())) {
            return;
        }
        List<AfterSalePolicy> policies = factory.createAfterSalePolicies();
        afterSalePolicyService.saveBatch(policies);
        log.info("⑨ 售后政策: {} 条", policies.size());
    }

    // ============================================================
    // ⑩ 用户券（阶段 5.9）
    // ============================================================

    /**
     * 给用户发券。
     *
     * <p>★ <b>这一步在 5.9 之前根本不存在</b> —— 尽管上面 {@code run()} 的注释里
     * 一直写着插入顺序包含 {@code user_coupon}。于是「我的优惠券」工具
     * （5.9 的交付物之一）在空库上只会返回「你没有券」。
     *
     * <p>★ <b>它读的用户和券模板都来自数据库</b>，不是上面几步传下来的对象 ——
     * 见类注释那条纪律。所以它可以独立重跑。
     */
    private void seedUserCoupons() {
        long existing = userCouponService.count();
        if (existing == 0) {
            insertUserCoupons();
        } else {
            log.info("⑩ 用户券: 已有 {} 条，跳过灌入", existing);
        }
        // ★ 无论灌没灌都同步一次。它是幂等的，而且能把「手工 SQL 灌了券、
        //   但没回写 issued_count」造成的不一致修掉 —— 那个不一致的症状是
        //   「券模板页面显示已发 0 张，而用户手里有券」
        syncIssuedCount();
    }

    private void insertUserCoupons() {
        List<AppUser> users = appUserService.list();
        List<Coupon> coupons = couponService.list();
        if (users.isEmpty() || coupons.isEmpty()) {
            log.warn("⑩ 跳过用户券：app_user={} 条，coupon={} 条 —— 两者都必须先有数据",
                    users.size(), coupons.size());
            return;
        }

        // ★ 每个用户【自己的】订单 id —— 「已使用」的券要挂到自己的订单上。
        //   只 select 两列，不把 120 笔订单整行读出来
        Map<Long, List<Long>> orderIdsByUser = new HashMap<>();
        for (Orders order : ordersService.lambdaQuery()
                .select(Orders::getId, Orders::getUserId).list()) {
            orderIdsByUser.computeIfAbsent(order.getUserId(), k -> new ArrayList<>())
                    .add(order.getId());
        }

        List<UserCoupon> issued = factory.createUserCoupons(users, coupons, orderIdsByUser);
        userCouponService.saveBatch(issued);

        log.info("⑩ 用户券: {} 条（{} 个用户，其中 {} 个名下有订单 —— 只有他们有已使用的券）",
                issued.size(), users.size(), orderIdsByUser.size());
    }

    /**
     * 把 {@code coupon.issued_count} 与实际发出的券数对齐。
     *
     * <p>{@code issued_count} 的语义是<b>历史累计发出</b>——用掉的和过期的都算，
     * 所以它等于 {@code user_coupon} 里该模板的行数。
     *
     * <p>⚠️ 数据库上有 {@code CHECK (issued_count <= total_count)}。
     * 本地种子数据远达不到上限（每张模板至少 500 张的额度，
     * 而 30 个用户每人最多领 4 张），但真实系统里这一步该带条件更新。
     */
    private void syncIssuedCount() {
        Map<Long, Long> actual = userCouponService.list().stream()
                .collect(Collectors.groupingBy(UserCoupon::getCouponId, Collectors.counting()));

        int changed = 0;
        for (Coupon coupon : couponService.list()) {
            int count = actual.getOrDefault(coupon.getId(), 0L).intValue();
            if (coupon.getIssuedCount() == null || coupon.getIssuedCount() != count) {
                coupon.setIssuedCount(count);
                couponService.updateById(coupon);
                changed++;
            }
        }
        if (changed > 0) {
            log.info("⑩ 已同步 {} 张券模板的 issued_count", changed);
        }
    }

    // ============================================================
    // 汇总
    // ============================================================

    /**
     * 统一的「这张表已经有数据了」判断，顺便打日志。
     *
     * <p>★ 抽出来是为了让「跳过」这件事<b>在每一处长得一模一样</b> ——
     * 包括日志的措辞。将来 grep 日志就能一次看全「这次跑了哪些、跳了哪些」。
     */
    private boolean skipIfPresent(String step, long count) {
        if (count > 0) {
            log.info("{}: 已有 {} 条，跳过", step, count);
            return true;
        }
        return false;
    }

    private void printSummary() {
        log.info("---------- 数据汇总 ----------");
        log.info("商品   : {}", productService.count());
        log.info("SKU    : {}", productSkuService.count());
        log.info("库存   : {}", inventoryService.count());
        log.info("用户   : {}", appUserService.count());
        log.info("优惠券 : {} 张模板 / {} 张已发出",
                couponService.count(), userCouponService.count());
        log.info("订单   : {}", ordersService.count());
        log.info("售后政策: {}", afterSalePolicyService.count());
        log.info("------------------------------");
    }

    private String randomSubset(List<String> list) {
        return list.get(random.nextInt(list.size()));
    }
}
