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
import com.xbla.rag.service.AfterSalePolicyService;
import com.xbla.rag.service.AppUserService;
import com.xbla.rag.service.CouponService;
import com.xbla.rag.service.InventoryService;
import com.xbla.rag.service.OrderItemService;
import com.xbla.rag.service.OrdersService;
import com.xbla.rag.service.ProductAttributeService;
import com.xbla.rag.service.ProductService;
import com.xbla.rag.service.ProductSkuService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

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
 * <p><b>幂等性</b>：开头会检查 product 表是否已有数据，
 * 非空就跳过。所以不小心跑两次也不会灌重复数据。
 *
 * <p><b>为什么不用 @Transactional 包起来？</b>
 * 灌 3000+ 条数据如果中途失败，包在事务里会全部回滚，
 * 但你看不到「卡在哪一步」。不包事务的话，失败点之前的进度是可见的，
 * 排查更直观——而且开头的幂等检查能防止重复灌入。
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
    private final AfterSalePolicyService afterSalePolicyService;

    private final SeedDataFactory factory = new SeedDataFactory();
    private final Random random = new Random(20260918L);

    @Override
    public void run(String... args) {
        long existing = productService.count();
        if (existing > 0) {
            log.warn("product 表已有 {} 条数据，跳过种子数据灌入。", existing);
            log.warn("如需重新灌入：docker compose down -v && docker compose up -d，再重跑本命令。");
            return;
        }

        long start = System.currentTimeMillis();
        log.info("=== 开始灌入种子数据 ===");

        // ★ 插入顺序不能乱：外键约束要求被引用的表先有数据。
        //   app_user → product → product_sku → inventory
        //                     → product_attribute
        //   coupon → orders → order_item → user_coupon
        List<AppUser> users = seedUsers();
        List<ProductBundleRef> products = seedProducts();
        seedCouponsAndOrders(users, products);
        seedAfterSalePolicies();

        log.info("=== 种子数据灌入完成，耗时 {} ms ===", System.currentTimeMillis() - start);
        printSummary();
    }

    // ============================================================
    // ① 用户
    // ============================================================

    private List<AppUser> seedUsers() {
        List<AppUser> users = factory.createUsers(USER_COUNT);
        // 逐个 save 而不是 saveBatch：后面生成订单时要用到用户的自增 id，
        // 逐个插入能确保 id 被立即回填到对象上。
        users.forEach(appUserService::save);
        log.info("① 用户: {} 条", users.size());
        return users;
    }

    // ============================================================
    // ② 商品 → ③ SKU → ④ 参数 → ⑤ 库存
    // ============================================================

    /** 商品 + 它插入后拿到 id 的 SKU 列表，供后面生成订单用 */
    private record ProductBundleRef(Product product, List<ProductSku> skus) {}

    private List<ProductBundleRef> seedProducts() {
        List<SeedDataFactory.ProductBundle> bundles = factory.createProducts(PRODUCT_COUNT);

        List<ProductBundleRef> refs = new ArrayList<>(bundles.size());
        List<ProductAttribute> allAttributes = new ArrayList<>();
        List<Inventory> allInventories = new ArrayList<>();

        for (SeedDataFactory.ProductBundle bundle : bundles) {
            Product product = bundle.product();

            // ② 插入商品，拿到自增 id
            productService.save(product);
            Long productId = product.getId();

            // ③ SKU：回填 productId 后插入，再拿到 skuId 建库存
            for (ProductSku sku : bundle.skus()) {
                sku.setProductId(productId);
                productSkuService.save(sku);
                allInventories.add(factory.createInventory(sku.getId()));
            }

            // ④ 商品参数：回填 productId
            List<ProductAttribute> attrs = bundle.attributes();
            attrs.forEach(a -> a.setProductId(productId));
            allAttributes.addAll(attrs);

            refs.add(new ProductBundleRef(product, bundle.skus()));
        }

        // ④ 和 ⑤ 是叶子节点，不需要回填 id，可以用批量插入
        productAttributeService.saveBatch(allAttributes);
        inventoryService.saveBatch(allInventories);

        log.info("② 商品: {} 条", refs.size());
        log.info("③ SKU: {} 条", refs.stream().mapToInt(r -> r.skus().size()).sum());
        log.info("④ 商品参数: {} 条", allAttributes.size());
        log.info("⑤ 库存: {} 条", allInventories.size());
        return refs;
    }

    // ============================================================
    // ⑥ 优惠券 → ⑦ 订单 → ⑧ 订单明细
    // ============================================================

    private void seedCouponsAndOrders(List<AppUser> users, List<ProductBundleRef> products) {
        List<Coupon> coupons = factory.createCoupons();
        coupons.forEach(couponService::save);
        log.info("⑥ 优惠券模板: {} 条", coupons.size());

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

    // ============================================================
    // ⑨ 售后政策
    // ============================================================

    private void seedAfterSalePolicies() {
        List<AfterSalePolicy> policies = factory.createAfterSalePolicies();
        afterSalePolicyService.saveBatch(policies);
        log.info("⑨ 售后政策: {} 条", policies.size());
    }

    // ============================================================
    // 汇总
    // ============================================================

    private void printSummary() {
        log.info("---------- 数据汇总 ----------");
        log.info("商品   : {}", productService.count());
        log.info("SKU    : {}", productSkuService.count());
        log.info("库存   : {}", inventoryService.count());
        log.info("用户   : {}", appUserService.count());
        log.info("订单   : {}", ordersService.count());
        log.info("售后政策: {}", afterSalePolicyService.count());
        log.info("------------------------------");
    }

    private String randomSubset(List<String> list) {
        return list.get(random.nextInt(list.size()));
    }
}
