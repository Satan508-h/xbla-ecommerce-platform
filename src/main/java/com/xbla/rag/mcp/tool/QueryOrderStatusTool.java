package com.xbla.rag.mcp.tool;

import com.xbla.rag.entity.OrderItem;
import com.xbla.rag.entity.Orders;
import com.xbla.rag.mcp.McpArguments;
import com.xbla.rag.mcp.McpTool;
import com.xbla.rag.mcp.McpToolContext;
import com.xbla.rag.mcp.McpToolResult;
import com.xbla.rag.mcp.ToolField;
import com.xbla.rag.service.OrderItemService;
import com.xbla.rag.service.OrdersService;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 「我的订单到哪了」—— 5.7 的端到端真工具。
 *
 * <h2>一、★★ 越权防护：{@code user_id} 来自上下文，不来自参数</h2>
 *
 * <pre>
 *   参数：{"order_no": "SO2026..."}        ← 只有订单号，模型能填的只有这个
 *   SQL ：WHERE order_no = ? AND user_id = ?   ← 第二个 ? 来自 {@link McpToolContext}
 * </pre>
 *
 * <p>这样就<b>不可能</b>通过构造参数查到别人的订单 —— 不管模型被怎么诱导。
 * 详见 {@link McpToolContext} 的类注释。
 *
 * <h2>二、★★ 查不到和越权返回【同一句话】</h2>
 *
 * <p>如果越权时回「无权查看此订单」、查不到时回「没有这个订单」，
 * 那么攻击者就有了一个<b>枚举预言机</b>：换个订单号，看回复是哪一个，
 * 就能逐个确认哪些订单号真实存在（哪怕拿不到内容）。
 *
 * <p>所以两种情况都回<b>同一句</b>「没有找到这个订单」。这不是偷懒，
 * 是唯一正确的做法 —— 而且它还有个好处：
 * 「没找到」对用户来说本来也是个完整的答案。
 *
 * <h2>三、状态码 → 人话</h2>
 *
 * <p>{@code orders.status} 是数字（10/20/30/40/50，见 {@code docs/04} 的状态机）。
 * <b>不把数字直接抛给模型</b>：模型看到 {@code status: 30} 会自己猜，
 * 而它可能猜成「30% 进度」或者「第 30 号订单」。
 * 结构化数据里两个都给（数字给程序，文字给模型），文本里只用文字。
 */
@Component
public class QueryOrderStatusTool implements McpTool {

    /** ★ 字段常量 —— schema 和取值都走它，见 {@link ToolField} 的类注释 */
    private static final ToolField ORDER_NO = ToolField.requiredString(
            "order_no",
            "订单号，形如 SO20260101123456。从用户那里获取，不要自己编造。");

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    // 状态机的文字映射。★ 和 docs/04 的图一一对应
    private static final Map<Integer, String> STATUS_TEXT = Map.of(
            10, "待付款",
            20, "待发货",
            30, "已发货",
            40, "已完成",
            50, "已取消");

    private final OrdersService ordersService;
    private final OrderItemService orderItemService;

    public QueryOrderStatusTool(OrdersService ordersService, OrderItemService orderItemService) {
        this.ordersService = ordersService;
        this.orderItemService = orderItemService;
    }

    @Override
    public String name() {
        return "query_order_status";
    }

    @Override
    public String title() {
        return "查询订单状态";
    }

    @Override
    public String description() {
        return """
                查询【当前用户自己的】某一笔订单的实时状态：到哪了、发货了没有、
                物流单号是什么、买了什么、实付多少。

                什么时候用：用户在问「我的」那一笔订单 ——
                「我的订单到哪了」「上周买的东西发货了吗」「订单什么时候能到」。

                什么时候【不要】用：
                - 问「一般发货要几天」这类通用规则 → 那是知识库的问题
                - 用户没给订单号，而且上下文里也没有 → 先问用户要订单号，
                  不要猜、不要编，也不要用别的订单号去试
                """;
    }

    @Override
    public List<ToolField> inputFields() {
        return List.of(ORDER_NO);
    }

    @Override
    public List<ToolField> outputFields() {
        return List.of(
                ToolField.requiredString("order_no", "订单号"),
                ToolField.requiredString("status_text", "订单状态的中文说明"),
                ToolField.requiredInt("status_code", "订单状态码（10待付款 20待发货 30已发货 40已完成 50已取消）"),
                ToolField.optionalString("logistics_company", "物流公司。未发货时为 null"),
                ToolField.optionalString("logistics_no", "物流单号。未发货时为 null"),
                ToolField.optionalString("created_at", "下单时间"),
                ToolField.optionalString("shipped_at", "发货时间。未发货时为 null"),
                ToolField.requiredString("items_summary", "商品清单的一句话描述"),
                ToolField.requiredString("pay_amount", "实付金额（元）"));
    }

    @Override
    public McpToolResult call(McpArguments args, McpToolContext context) {
        String orderNo = ORDER_NO.requireString(args);

        // ★★ 身份来自 context，不来自 args。SQL 里两个条件缺一不可
        Orders order = ordersService.lambdaQuery()
                .eq(Orders::getOrderNo, orderNo)
                .eq(Orders::getUserId, context.userId())
                .eq(Orders::getDeleted, 0)
                .last("LIMIT 1")
                .one();

        if (order == null) {
            // ★ 见类注释第二节：「不存在」和「不是你的」返回【同一句】
            return McpToolResult.ok(
                    "没有找到订单号 " + orderNo + " 对应的订单。"
                            + "请让用户核对一下订单号 —— 也可能是这笔订单不属于当前账号。");
        }

        List<OrderItem> items = orderItemService.lambdaQuery()
                .eq(OrderItem::getOrderId, order.getId())
                .orderByAsc(OrderItem::getId)
                .list();

        String statusText = STATUS_TEXT.getOrDefault(order.getStatus(), "未知状态");

        return McpToolResult.ok(
                renderText(order, statusText, items),
                renderData(order, statusText, items));
    }

    // ============================================================
    // 两种输出
    // ============================================================

    /** 给模型读的正文。★ 只用中文状态，不给数字状态码 */
    private static String renderText(Orders order, String statusText, List<OrderItem> items) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("订单 ").append(order.getOrderNo())
                .append(" 当前状态：").append(statusText).append('\n');

        sb.append("下单时间：").append(format(order.getCreatedAt())).append('\n');

        if (order.getLogisticsNo() != null && !order.getLogisticsNo().isBlank()) {
            sb.append("物流：").append(nullToDash(order.getLogisticsCompany()))
                    .append(' ').append(order.getLogisticsNo()).append('\n');
            sb.append("发货时间：").append(format(order.getShippedAt())).append('\n');
        } else if ("已发货".equals(statusText) || "已完成".equals(statusText)) {
            // ★ 这是一个真实存在的不一致：状态说发货了，但没有物流单号。
            //   实测种子数据里物流单号只挂在 30/40 上，所以正常不该走到这里 ——
            //   真走到了就如实说，别让模型编一个单号出来
            sb.append("物流：状态显示已发货，但系统里没有物流单号，请以订单页为准\n");
        }

        sb.append("商品：").append(itemsSummary(items)).append('\n');
        sb.append("实付：").append(order.getPayAmount()).append(" 元\n");

        if ("已取消".equals(statusText) && order.getCancelledAt() != null) {
            sb.append("取消时间：").append(format(order.getCancelledAt())).append('\n');
        }
        if ("已完成".equals(statusText) && order.getCompletedAt() != null) {
            sb.append("完成时间：").append(format(order.getCompletedAt())).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    /** 机器可读的结构化数据，配套 {@link #outputFields()} */
    private static Map<String, Object> renderData(Orders order, String statusText,
                                                  List<OrderItem> items) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("order_no", order.getOrderNo());
        data.put("status_text", statusText);
        data.put("status_code", order.getStatus());
        data.put("logistics_company", order.getLogisticsCompany());
        data.put("logistics_no", order.getLogisticsNo());
        data.put("created_at", format(order.getCreatedAt()));
        data.put("shipped_at", format(order.getShippedAt()));
        data.put("items_summary", itemsSummary(items));
        data.put("pay_amount", order.getPayAmount().toPlainString());
        return data;
    }

    /**
     * 商品清单压成一句话。
     *
     * <p>★ 用 {@code order_item} 的<b>快照字段</b>（{@code product_name} / {@code spec_name}），
     * 不是去 join {@code product} 和 {@code product_sku} 拿当前值 ——
     * 商品改名或改规格之后，用户的订单应该显示<b>他当时买的是什么</b>。
     * 那三列就是为这件事存的（见 {@code docs/04}）。
     */
    private static String itemsSummary(List<OrderItem> items) {
        if (items == null || items.isEmpty()) {
            // 数据异常（订单没有明细）。如实说，别编
            return "（这笔订单没有商品明细记录）";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            OrderItem item = items.get(i);
            if (i > 0) {
                sb.append("；");
            }
            sb.append(item.getProductName());
            if (item.getSpecName() != null && !item.getSpecName().isBlank()) {
                sb.append('（').append(item.getSpecName()).append('）');
            }
            sb.append(" ×").append(item.getQuantity());
        }
        return sb.toString();
    }

    private static String format(OffsetDateTime time) {
        return time == null ? null : DATE.format(time);
    }

    private static String nullToDash(String value) {
        return value == null || value.isBlank() ? "（物流公司未填）" : value;
    }
}
