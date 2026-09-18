package com.xbla.rag.seed;

import com.xbla.rag.entity.AfterSalePolicy;
import com.xbla.rag.entity.AppUser;
import com.xbla.rag.entity.Coupon;
import com.xbla.rag.entity.Inventory;
import com.xbla.rag.entity.Product;
import com.xbla.rag.entity.ProductAttribute;
import com.xbla.rag.entity.ProductSku;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 种子数据工厂。
 *
 * <p><b>为什么用「工厂」而不是直接在 Runner 里写？</b>
 * 生成数据的逻辑（模板、随机组合、字段规则）和「把数据写进数据库」的逻辑
 * 是两件不同的事。分开之后，工厂可以单独测试（不连数据库也能验证生成的数据是否合理），
 * Runner 只管批量插入。
 *
 * <p><b>数据是可复现的</b>：{@link Random} 用固定种子初始化，
 * 所以每次运行生成的数据完全一致。这对评测很重要——
 * 阶段 7 做 A/B 对比时，两轮必须跑在<b>同一批数据</b>上，
 * 否则指标差异可能只是数据不同造成的，而不是优化带来的。
 */
public class SeedDataFactory {

    /**
     * 固定随机种子。
     *
     * <p>换一个数字就会得到一整套不同的数据。写死是为了可复现——
     * 见类注释。
     */
    private static final long RANDOM_SEED = 20260918L;

    private final Random random = new Random(RANDOM_SEED);
    /** 商品编号递增序号，保证唯一 */
    private int productCounter = 0;

    // ============================================================
    // 类目定义：每个类目的品牌、型号命名规则、价格区间、规格维度
    // ============================================================

    /**
     * 类目模板。
     *
     * @param category    类目名
     * @param subCategory 子类目候选
     * @param brands      品牌候选
     * @param series      型号系列候选
     * @param suffixes    型号后缀候选（Pro / Max / Ultra…）
     * @param priceMin    价格下限
     * @param priceMax    价格上限
     * @param specKeys    规格维度名（如"颜色"、"存储"）
     * @param specValues  每个规格维度的取值
     */
    private record CategoryTemplate(
            String category,
            List<String> subCategory,
            List<String> brands,
            List<String> series,
            List<String> suffixes,
            int priceMin,
            int priceMax,
            List<String> specKeys,
            Map<String, List<String>> specValues
    ) {}

    private static final List<CategoryTemplate> TEMPLATES = List.of(
            new CategoryTemplate(
                    "手机",
                    List.of("智能手机", "折叠屏手机"),
                    List.of("华为", "小米", "苹果", "荣耀", "OPPO", "vivo"),
                    List.of("Mate", "P", "Redmi", "iPhone", "Magic", "Find", "X"),
                    List.of("", " Pro", " Pro Max", " Ultra", " mini"),
                    999, 8999,
                    List.of("颜色", "存储"),
                    Map.of(
                            "颜色", List.of("星空黑", "月光银", "晨曦金", "远山蓝", "樱语粉"),
                            "存储", List.of("128GB", "256GB", "512GB", "1TB")
                    )
            ),
            new CategoryTemplate(
                    "笔记本电脑",
                    List.of("轻薄本", "游戏本", "商务本"),
                    List.of("联想", "华为", "苹果", "华硕", "戴尔", "惠普"),
                    List.of("ThinkPad", "MateBook", "MacBook", "灵越", "战 66", "小新"),
                    List.of(" Air", " Pro", " 14", " 16", " Plus"),
                    3999, 15999,
                    List.of("颜色", "内存", "硬盘"),
                    Map.of(
                            "颜色", List.of("深空灰", "银色", "午夜黑"),
                            "内存", List.of("16GB", "32GB"),
                            "硬盘", List.of("512GB SSD", "1TB SSD", "2TB SSD")
                    )
            ),
            new CategoryTemplate(
                    "平板电脑",
                    List.of("娱乐平板", "生产力平板"),
                    List.of("苹果", "华为", "小米", "荣耀", "三星"),
                    List.of("iPad", "MatePad", "MiPad", "Galaxy Tab"),
                    List.of("", " Air", " Pro", " mini", " SE"),
                    1499, 7999,
                    List.of("颜色", "存储"),
                    Map.of(
                            "颜色", List.of("深空灰", "星光色", "紫色"),
                            "存储", List.of("64GB", "128GB", "256GB", "512GB")
                    )
            ),
            new CategoryTemplate(
                    "耳机",
                    List.of("真无线耳机", "头戴式耳机", "颈挂式耳机"),
                    List.of("索尼", "苹果", "华为", "小米", "Bose", "森海塞尔"),
                    List.of("WF", "AirPods", "FreeBuds", "Buds", "QuietComfort"),
                    List.of("", " Pro", " Plus", " 2", " 3"),
                    199, 2499,
                    List.of("颜色", "版本"),
                    Map.of(
                            "颜色", List.of("曜石黑", "陶瓷白", "海盐蓝"),
                            "版本", List.of("标准版", "降噪版")
                    )
            ),
            new CategoryTemplate(
                    "智能穿戴",
                    List.of("智能手表", "智能手环"),
                    List.of("华为", "小米", "苹果", "佳明", "荣耀"),
                    List.of("Watch", "手环", "Apple Watch", "Forerunner"),
                    List.of("", " GT", " Pro", " SE", " 2"),
                    299, 3999,
                    List.of("颜色", "表带材质"),
                    Map.of(
                            "颜色", List.of("曜夜黑", "星光银", "活力橙"),
                            "表带材质", List.of("氟橡胶", "真皮", "尼龙")
                    )
            ),
            new CategoryTemplate(
                    "家用电器",
                    List.of("冰箱", "洗衣机", "空调", "吸尘器", "空气净化器"),
                    List.of("美的", "戴森", "海尔", "格力", "小米", "西门子"),
                    List.of("BCD", "V", "KFR", "GT", "米家", "iQ"),
                    List.of("", " 系列", " Pro", " Plus"),
                    399, 9999,
                    List.of("颜色", "容量/型号"),
                    Map.of(
                            "颜色", List.of("珍珠白", "星空银", "钛金灰"),
                            "容量/型号", List.of("标准款", "大容量款", "旗舰款")
                    )
            )
    );

    /** 卖点池，按类目给不同的候选 */
    private static final Map<String, List<String>> SELLING_POINTS = Map.of(
            "手机", List.of("续航 18 小时", "5000mAh 大电池", "1 亿像素主摄", "120Hz 高刷屏",
                    "支持 66W 快充", "超薄机身 7.8mm", "卫星通信", "IP68 防水"),
            "笔记本电脑", List.of("重量仅 1.2kg", "16 小时长续航", "2.8K 高色域屏",
                    "独立显卡", "全金属机身", "指纹+人脸双解锁", "雷电 4 接口"),
            "平板电脑", List.of("120Hz 高刷", "支持手写笔", "四扬声器", "12 小时续航",
                    "轻薄便携", "支持键盘扩展"),
            "耳机", List.of("主动降噪 45dB", "续航 36 小时", "空间音频", "低延迟游戏模式",
                    "IPX4 防水", "通话降噪"),
            "智能穿戴", List.of("血氧监测", "心率监测", "14 天续航", "100+ 运动模式",
                    "睡眠分析", "独立通话"),
            "家用电器", List.of("一级能效", "静音运行", "智能联网", "自清洁", "大容量",
                    "除菌除螨", "APP 远程控制")
    );

    /**
     * 适用场景池。
     *
     * <p>★ 这个字段是为 RAG 场景专门造的。阶段 5 的演示问题
     * "这个适合送长辈吗" 就是靠它才能被语义检索命中——
     * 如果只存规格参数，向量检索会匹配到"6.7 英寸屏幕"这种无关内容。
     */
    private static final List<String> SUITABLE_FOR = List.of(
            "适合送长辈、操作简单",
            "适合商务人士、办公场景",
            "适合学生党、性价比之选",
            "适合游戏玩家、高性能需求",
            "适合运动健身、防水防汗",
            "适合长途通勤、降噪需求",
            "适合家庭使用、大容量",
            "适合送礼、包装精美",
            "适合女生、轻巧便携",
            "适合摄影爱好者、影像旗舰"
    );

    // ============================================================
    // 商品
    // ============================================================

    /** 一个商品连带它的 SKU 和参数 */
    public record ProductBundle(Product product,
                                List<ProductSku> skus,
                                List<ProductAttribute> attributes) {}

    /**
     * 生成约 {@code totalCount} 个商品，均分到各类目。
     */
    public List<ProductBundle> createProducts(int totalCount) {
        List<ProductBundle> bundles = new ArrayList<>(totalCount);
        int perCategory = totalCount / TEMPLATES.size();
        int remainder = totalCount % TEMPLATES.size();

        for (int i = 0; i < TEMPLATES.size(); i++) {
            CategoryTemplate template = TEMPLATES.get(i);
            // 除不尽的部分摊到前几个类目，保证总数正好等于 totalCount
            int count = perCategory + (i < remainder ? 1 : 0);
            for (int j = 0; j < count; j++) {
                bundles.add(createOneProduct(template));
            }
        }
        return bundles;
    }

    private ProductBundle createOneProduct(CategoryTemplate t) {
        productCounter++;
        String brand = pick(t.brands());
        String series = pick(t.series());
        String suffix = pick(t.suffixes());
        String modelName = brand + " " + series + suffix;

        Product product = new Product();
        product.setProductNo(String.format("P%06d", productCounter));
        product.setName(modelName);
        product.setCategory(t.category());
        product.setSubCategory(pick(t.subCategory()));
        product.setBrand(brand);

        BigDecimal price = randomPrice(t.priceMin(), t.priceMax());
        product.setPrice(price);
        // 划线价 = 售价上浮 10% ~ 35%，制造折扣感
        product.setOriginalPrice(price
                .multiply(BigDecimal.valueOf(1 + random.nextInt(26) / 100.0))
                .setScale(2, RoundingMode.HALF_UP));
        product.setSellingPoints(String.join(" / ",
                randomSubset(SELLING_POINTS.get(t.category()), 2, 4)));
        product.setSuitableFor(pick(SUITABLE_FOR));
        product.setDescription(buildDescription(product));
        // 少量商品下架，让数据更真实（也让阶段 5 的"过滤下架商品"有东西可测）
        product.setStatus(random.nextInt(20) == 0 ? 0 : 1);

        List<ProductSku> skus = createSkusFor(product, t);
        List<ProductAttribute> attributes = createAttributesFor(product, t);
        return new ProductBundle(product, skus, attributes);
    }

    private List<ProductSku> createSkusFor(Product product, CategoryTemplate t) {
        // 枚举出规格的所有组合
        List<Map<String, String>> combos = cartesian(t.specKeys(), t.specValues());
        // 随机挑 2~4 个组合作为该商品的 SKU
        int skuCount = Math.min(combos.size(), 2 + random.nextInt(3));
        List<Map<String, String>> chosen = randomSubset(combos, skuCount, skuCount);

        List<ProductSku> skus = new ArrayList<>();
        for (int i = 0; i < chosen.size(); i++) {
            Map<String, String> spec = chosen.get(i);
            ProductSku sku = new ProductSku();
            // ⚠️ productId 在这里<b>故意不设</b>：商品还没插入数据库，
            //    自增主键还不知道。等 Runner 插入完商品拿到 id 之后再回填。
            sku.setSkuNo(product.getProductNo() + "-" + (i + 1));
            sku.setSpecName(String.join(" ", spec.values()));
            sku.setSpecJson(toJson(spec));
            // SKU 价格在商品价上下浮动 15%
            sku.setPrice(product.getPrice()
                    .multiply(BigDecimal.valueOf(0.9 + random.nextInt(31) / 100.0))
                    .setScale(2, RoundingMode.HALF_UP));
            sku.setBarcode("69" + String.format("%011d", random.nextInt(1_000_000_000)));
            sku.setStatus(1);
            skus.add(sku);
        }
        return skus;
    }

    private List<ProductAttribute> createAttributesFor(Product product, CategoryTemplate t) {
        // 参数池按类目给不同的组和参数名
        List<String[]> pool = switch (t.category()) {
            case "手机" -> List.of(
                    new String[]{"基本参数", "上市年份", "2026 年"},
                    new String[]{"基本参数", "操作系统", "HarmonyOS / Android / iOS"},
                    new String[]{"屏幕", "屏幕尺寸", pick(List.of("6.1 英寸", "6.7 英寸", "6.8 英寸"))},
                    new String[]{"屏幕", "刷新率", pick(List.of("60Hz", "120Hz", "144Hz"))},
                    new String[]{"性能", "处理器", pick(List.of("旗舰八核", "高性能八核", "中端八核"))},
                    new String[]{"性能", "运行内存", pick(List.of("8GB", "12GB", "16GB"))},
                    new String[]{"影像", "后置摄像头", pick(List.of("5000万像素双摄", "1亿像素三摄", "5000万像素四摄"))},
                    new String[]{"电池", "电池容量", pick(List.of("4500mAh", "5000mAh", "5500mAh"))}
            );
            case "笔记本电脑" -> List.of(
                    new String[]{"基本参数", "上市年份", "2026 年"},
                    new String[]{"屏幕", "屏幕尺寸", pick(List.of("14 英寸", "15.6 英寸", "16 英寸"))},
                    new String[]{"屏幕", "分辨率", pick(List.of("1920×1200", "2560×1600", "2880×1800"))},
                    new String[]{"性能", "处理器", pick(List.of("酷睿 i5", "酷睿 i7", "锐龙 7", "M 系列芯片"))},
                    new String[]{"性能", "显卡", pick(List.of("集成显卡", "独立显卡 RTX 4060", "独立显卡 RTX 4070"))},
                    new String[]{"接口", "USB 接口", pick(List.of("2×USB-A + 2×USB-C", "3×USB-C"))},
                    new String[]{"电池", "续航时间", pick(List.of("8 小时", "12 小时", "16 小时"))}
            );
            case "耳机" -> List.of(
                    new String[]{"基本参数", "连接方式", "蓝牙 5.4"},
                    new String[]{"基本参数", "佩戴方式", pick(List.of("入耳式", "头戴式", "半入耳式"))},
                    new String[]{"音频", "降噪深度", pick(List.of("无降噪", "主动降噪 35dB", "主动降噪 45dB"))},
                    new String[]{"音频", "音频编码", pick(List.of("SBC/AAC", "LDAC", "LHDC 5.0"))},
                    new String[]{"电池", "单次续航", pick(List.of("6 小时", "8 小时", "10 小时"))},
                    new String[]{"电池", "总续航", pick(List.of("24 小时", "36 小时", "48 小时"))}
            );
            default -> List.of(
                    new String[]{"基本参数", "上市年份", "2026 年"},
                    new String[]{"基本参数", "保修政策", "全国联保一年"},
                    new String[]{"外观", "颜色", pick(List.of("珍珠白", "星空银", "曜石黑"))},
                    new String[]{"性能", "核心参数", pick(List.of("标准配置", "高配版", "旗舰配置"))},
                    new String[]{"服务", "是否支持退换", "支持七天无理由退换"}
            );
        };

        List<ProductAttribute> attrs = new ArrayList<>();
        for (int i = 0; i < pool.size(); i++) {
            String[] row = pool.get(i);
            ProductAttribute attr = new ProductAttribute();
            // 同 SKU：productId 由 Runner 在商品插入后回填
            attr.setAttrGroup(row[0]);
            attr.setAttrName(row[1]);
            attr.setAttrValue(row[2]);
            attr.setSortOrder(i);
            attrs.add(attr);
        }
        return attrs;
    }

    // ============================================================
    // 用户
    // ============================================================

    private static final List<String> NICKNAME_PREFIX = List.of(
            "小明", "阿杰", "晨曦", "子墨", "婉清", "一诺", "思远", "雨桐",
            "沐辰", "若曦", "亦凡", "安然", "嘉懿", "静姝", "昊然", "嘉航");

    public List<AppUser> createUsers(int count) {
        List<AppUser> users = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            AppUser user = new AppUser();
            user.setUserNo(String.format("U%06d", i));
            user.setNickname(pick(NICKNAME_PREFIX) + i);
            // ★ 手机号脱敏存储。虽然是仿真数据，但真实项目里手机号属敏感信息，
            //   明文入库一旦泄露后果严重，这个习惯要在写代码时就养成。
            user.setPhone(String.format("1%d%d****%04d",
                    random.nextInt(10), random.nextInt(10), random.nextInt(10000)));
            // 大部分是普通会员，少量高等级——贴近真实分布
            user.setMemberLevel(switch (random.nextInt(10)) {
                case 0, 1 -> 2;
                case 2 -> 3;
                case 3 -> 4;
                default -> 1;
            });
            users.add(user);
        }
        return users;
    }

    // ============================================================
    // 库存
    // ============================================================

    public Inventory createInventory(Long skuId) {
        int total = random.nextInt(500) + 10;
        // 少量 SKU 库存为 0，用于测试阶段 5「库存不足」的问答路径
        int available = random.nextInt(25) == 0 ? 0 : total - random.nextInt(total / 3 + 1);
        Inventory inventory = new Inventory();
        inventory.setSkuId(skuId);
        inventory.setTotalStock(total);
        inventory.setAvailableStock(Math.max(available, 0));
        inventory.setLockedStock(total - Math.max(available, 0));
        inventory.setWarehouse(pick(List.of("华东仓-上海", "华北仓-北京", "华南仓-广州", "西南仓-成都")));
        inventory.setVersion(0);
        return inventory;
    }

    // ============================================================
    // 售后政策
    // ============================================================

    /**
     * 售后政策。
     *
     * <p>★ 这里的 {@code content} 是阶段 3 知识库的语料来源之一。
     * 写得尽量像真实政策文本（有条款、有条件、有例外），
     * 这样切分和向量化出来的切片才有实际检索价值。
     */
    public List<AfterSalePolicy> createAfterSalePolicies() {
        return List.of(
                policy("AS-001", null, "七天无理由退货政策", 7, 15,
                        "自签收之日起 7 天内，商品未拆封、不影响二次销售的，支持无理由退货。"
                                + "退货时需保持商品、配件、赠品、包装完整，并附带发票或购买凭证。"
                                + "退货运费由消费者承担；若为商品质量问题，运费由平台承担。",
                        "手机、电脑等数码产品拆封激活后不支持无理由退货；定制类商品不支持无理由退货。"),
                policy("AS-002", "手机", "手机类商品售后规则", 7, 15,
                        "手机类商品自签收之日起 7 天内支持无理由退货，15 天内支持换货。"
                                + "★ 已激活的手机不支持无理由退货，但若存在质量问题，"
                                + "凭厂商检测报告可在 15 天内换货、30 天内维修。",
                        "手机一经激活即绑定保修，非质量问题不支持退货；屏幕划痕等外观问题需在签收 24 小时内提出。"),
                policy("AS-003", "笔记本电脑", "电脑类商品售后规则", 7, 15,
                        "电脑类商品自签收之日起 7 天内支持无理由退货，15 天内支持换货，"
                                + "整机保修 1 年，主要部件保修 2 年。"
                                + "保修期内非人为损坏免费维修，人为损坏需支付材料费。",
                        "私自拆机、刷机、进水、摔损等人为损坏不在保修范围内。"),
                policy("AS-004", "耳机", "耳机类商品售后规则", 7, 15,
                        "耳机类商品自签收之日起 7 天内支持无理由退货，15 天内支持换货。"
                                + "★ 出于卫生考虑，入耳式耳机拆封后不支持无理由退货，"
                                + "但质量问题仍按三包政策处理。",
                        "入耳式耳机拆封后不支持无理由退货；头戴式耳机不影响二次销售可退。"),
                policy("AS-005", "家电", "大家电售后规则", 7, 30,
                        "大家电自签收之日起 7 天内支持无理由退货，30 天内支持换货。"
                                + "大家电退货需保留原包装，且需自行承担往返运费（单件最高 300 元）。"
                                + "安装后产生的拆机费用由消费者承担。",
                        "已安装的大家电退货需支付拆机费；无原包装的需支付包装费。"),
                policy("AS-006", "智能穿戴", "智能穿戴售后规则", 7, 15,
                        "智能手表、手环自签收之日起 7 天内支持无理由退货，15 天内支持换货。"
                                + "表带属于易耗品，不在保修范围内，但可在签收 7 天内因质量问题更换。",
                        "表带、充电线等配件不在整机保修范围内。"),
                policy("AS-007", null, "价格保护政策", null, null,
                        "商品自下单之日起 15 天内，若同一商品在同一平台出现降价，"
                                + "可申请价格保护，退还差价。"
                                + "参与限时秒杀、拼团、清仓等特殊活动的商品不适用价格保护。",
                        "需提供降价截图；每笔订单仅可申请一次价格保护。"),
                policy("AS-008", null, "发票与保修凭证说明", null, null,
                        "平台默认开具电子发票，可在订单详情页下载。"
                                + "如需纸质发票，请在下单时备注。"
                                + "保修凭证为电子发票或订单截图，请妥善保存。"
                                + "发票抬头与订单主体需一致，已开具的发票不支持修改抬头。",
                        "发票已开具后不支持更改抬头；退货时需一并退回发票。"),
                policy("AS-009", null, "退款到账时间说明", null, null,
                        "退货商品签收并验收通过后，退款将在 1-3 个工作日内原路返回。"
                                + "微信/支付宝支付通常 24 小时内到账；"
                                + "银行卡支付因银行处理时效不同，可能需要 3-7 个工作日。"
                                + "使用优惠券支付的订单，优惠券在有效期内将原路返还。",
                        "已过期的优惠券不予返还；组合支付按原支付方式分别退回。"),
                policy("AS-010", "平板电脑", "平板类商品售后规则", 7, 15,
                        "平板电脑自签收之日起 7 天内支持无理由退货，15 天内支持换货。"
                                + "已激活的平板不支持无理由退货，质量问题按三包政策处理。"
                                + "配套的手写笔、键盘等配件享受同等售后政策。",
                        "平板激活后不支持无理由退货；贴膜、保护壳等配件仅支持质量问题换货。"),
                policy("AS-011", null, "以旧换新服务说明", null, null,
                        "平台支持部分品类的以旧换新服务，旧机估价由第三方检测机构评估。"
                                + "旧机估价在下单后 7 天内有效，逾期需重新估价。"
                                + "旧机寄出后如检测结果与预估不符，将重新报价，消费者可选择接受或退回。",
                        "以旧换新补贴不与平台优惠券叠加使用。"),
                policy("AS-012", null, "物流损坏处理流程", null, null,
                        "签收时请当面验货，如发现外包装破损，请拒收并拍照留证。"
                                + "若签收后发现商品损坏，请在 24 小时内联系客服并提供开箱视频。"
                                + "经核实为物流责任的，平台全额赔付并承担运费。",
                        "超过 24 小时未反馈的物流损坏，需提供开箱视频方可受理。")
        );
    }

    private AfterSalePolicy policy(String no, String category, String title,
                                   Integer returnDays, Integer exchangeDays,
                                   String content, String conditions) {
        AfterSalePolicy p = new AfterSalePolicy();
        p.setPolicyNo(no);
        p.setCategory(category);
        p.setTitle(title);
        p.setContent(content);
        p.setReturnDays(returnDays);
        p.setExchangeDays(exchangeDays);
        p.setConditions(conditions);
        p.setEffectiveFrom(OffsetDateTime.now().minusMonths(6));
        p.setEffectiveTo(null);          // null 表示长期有效
        p.setVersion(1);
        p.setStatus(1);
        return p;
    }

    // ============================================================
    // 优惠券
    // ============================================================

    public List<Coupon> createCoupons() {
        List<Coupon> coupons = new ArrayList<>();
        coupons.add(coupon("C-001", "全场满 1000 减 100", 1,
                new BigDecimal("100"), null, new BigDecimal("1000"), null, 10000));
        coupons.add(coupon("C-002", "全场满 3000 减 400", 1,
                new BigDecimal("400"), null, new BigDecimal("3000"), null, 5000));
        coupons.add(coupon("C-003", "手机品类满 5000 减 600", 1,
                new BigDecimal("600"), null, new BigDecimal("5000"), "手机", 2000));
        coupons.add(coupon("C-004", "电脑品类 9 折券", 2,
                null, new BigDecimal("0.90"), new BigDecimal("3000"), "笔记本电脑", 1500));
        coupons.add(coupon("C-005", "耳机品类立减 50", 3,
                new BigDecimal("50"), null, BigDecimal.ZERO, "耳机", 8000));
        coupons.add(coupon("C-006", "新人专享 200 元券", 3,
                new BigDecimal("200"), null, new BigDecimal("200"), null, 20000));
        coupons.add(coupon("C-007", "家电品类满 8000 减 800", 1,
                new BigDecimal("800"), null, new BigDecimal("8000"), "家用电器", 500));
        coupons.add(coupon("C-008", "智能穿戴 95 折", 2,
                null, new BigDecimal("0.95"), new BigDecimal("500"), "智能穿戴", 3000));
        return coupons;
    }

    private Coupon coupon(String no, String name, int type, BigDecimal value,
                          BigDecimal rate, BigDecimal threshold, String category, int total) {
        Coupon c = new Coupon();
        c.setCouponNo(no);
        c.setName(name);
        c.setType(type);
        c.setDiscountValue(value);
        c.setDiscountRate(rate);
        c.setThresholdAmount(threshold);
        c.setApplicableCategory(category);
        c.setTotalCount(total);
        c.setIssuedCount(0);          // 领券时再累加
        c.setValidFrom(OffsetDateTime.now().minusDays(30));
        c.setValidTo(OffsetDateTime.now().plusDays(60));
        c.setStatus(1);
        return c;
    }

    // ============================================================
    // 工具方法
    // ============================================================

    private <T> T pick(List<T> list) {
        return list.get(random.nextInt(list.size()));
    }

    /** 从列表里随机取 [min, max] 个不重复的元素 */
    private <T> List<T> randomSubset(List<T> list, int min, int max) {
        int count = Math.min(list.size(), min + random.nextInt(max - min + 1));
        List<T> copy = new ArrayList<>(list);
        java.util.Collections.shuffle(copy, random);
        return new ArrayList<>(copy.subList(0, count));
    }

    private BigDecimal randomPrice(int min, int max) {
        // 价格取整到 9 结尾，更像真实定价
        int value = min + random.nextInt(max - min);
        return BigDecimal.valueOf(value / 10 * 10 + 9);
    }

    /** 生成规格的所有笛卡尔积组合 */
    private List<Map<String, String>> cartesian(List<String> keys, Map<String, List<String>> values) {
        List<Map<String, String>> result = new ArrayList<>();
        result.add(new LinkedHashMap<>());
        for (String key : keys) {
            List<Map<String, String>> next = new ArrayList<>();
            for (Map<String, String> base : result) {
                for (String value : values.get(key)) {
                    Map<String, String> extended = new LinkedHashMap<>(base);
                    extended.put(key, value);
                    next.add(extended);
                }
            }
            result = next;
        }
        return result;
    }

    /** 手写一个极简 JSON 序列化，避免为一个 Map 引入 Jackson 依赖 */
    private String toJson(Map<String, String> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> e : map.entrySet()) {
            if (!first) sb.append(',');
            sb.append('"').append(e.getKey()).append("\":\"").append(e.getValue()).append('"');
            first = false;
        }
        return sb.append('}').toString();
    }

    private String buildDescription(Product p) {
        return p.getName() + "，" + p.getSubCategory() + "。" + p.getSellingPoints()
                + "。适用场景：" + p.getSuitableFor() + "。";
    }
}
