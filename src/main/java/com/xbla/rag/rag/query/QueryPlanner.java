package com.xbla.rag.rag.query;

/**
 * 查询计划器：把用户原话加工成检索友好的形式。
 *
 * <p>覆盖路线图的 4.3（查询重写）和 4.4（子问题拆分）——
 * 两者都是「检索之前对查询做的加工」，共用一个输入输出，所以放在一个接口里。
 *
 * <h2>★ 为什么默认关闭</h2>
 *
 * <p>两个实现类靠配置开关切换，默认走「不加工」那一支。
 *
 * <p>这不是为了省事，是为了<b>让基线干净</b>：如果默认开启，
 * 阶段 4 记录下来的基线指标就带着重写的影响，阶段 7 的 A/B
 * 就无法回答「这次的提升来自重写，还是来自切分粒度/TopK/RRF 参数」。
 * <b>一次只引入一个变量</b>，是能做归因的前提。
 *
 * <h2>★ 为什么定义成接口而不是「一个类加个 if」</h2>
 *
 * <p>因为这两种形态的<b>依赖完全不同</b>：
 * {@link NoOpQueryPlanner} 什么都不需要，
 * {@link LlmQueryPlanner} 要注入 {@code ChatModelRouter}、{@code ObjectMapper}。
 * 用一个 {@code if (enabled)} 包起来的话，关闭状态下那些依赖仍然会被创建、
 * 仍然会参与启动期校验（比如 API Key 缺失的告警），
 * 而且测试时没法「只测关闭路径、不引入模型客户端」。
 */
public interface QueryPlanner {

    /**
     * 生成查询计划。
     *
     * <p><b>实现必须保证不抛异常。</b>查询加工是「锦上添花」的一环，
     * 模型调用失败、JSON 解析失败、超时 —— 一律回落到
     * {@link QueryPlan#identity(String)} 并带一条 {@code note}。
     * 用户问一个问题，不该因为改写失败就得不到回答。
     *
     * @param question 用户原话
     * @return 查询计划，<b>永不为 null</b>
     */
    QueryPlan plan(String question);
}
