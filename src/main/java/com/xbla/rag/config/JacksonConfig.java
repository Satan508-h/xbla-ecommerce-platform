package com.xbla.rag.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * 对外 HTTP 接口的 JSON 序列化配置。
 *
 * <h2>★ 为什么需要这个类（一次真实的踩坑记录）</h2>
 *
 * <p>这个类的存在，是为了修一个 Spring Boot 里非常经典、但报错信息
 * 完全指不到根因的问题。
 *
 * <p><b>症状</b>：新增的知识库状态查询接口一调用就 500，日志里是：
 * <pre>
 *   Java 8 date/time type `java.time.OffsetDateTime` not supported by default:
 *   add Module "com.fasterxml.jackson.datatype:jackson-datatype-jsr310" to enable handling
 * </pre>
 *
 * <p>按照这条提示去查，第一反应是「少了 jsr310 依赖」。但查依赖树发现
 * {@code jackson-datatype-jsr310:2.21.4} <b>明明就在 classpath 上</b>
 * （由 {@code spring-boot-starter-web} 传递引入）。所以问题不是「缺依赖」，
 * 而是「模块没被注册」。
 *
 * <p><b>根因链条</b>
 * <pre>
 *   ① {@link HttpClientConfig} 定义了 @Bean("modelObjectMapper") ObjectMapper
 *                          ↓
 *   ② Spring Boot 的 JacksonAutoConfiguration.jacksonObjectMapper()
 *      上标着 @ConditionalOnMissingBean —— 它看到容器里【已经有】ObjectMapper 了，
 *      于是【整个自动配置退让】，那个配好 JavaTimeModule、ParameterNamesModule
 *      等等一系列模块的 primary mapper 从未被创建
 *                          ↓
 *   ③ Spring MVC 的 MappingJackson2HttpMessageConverter 是按类型注入 ObjectMapper 的，
 *      容器里只剩我那一个「模型协议专用」的 mapper，它没有 JavaTimeModule
 *                          ↓
 *   ④ OffsetDateTime 序列化失败
 * </pre>
 *
 * <p><b>这个坑的可怕之处</b>：两件毫不相干的事被隐式绑在了一起。
 * {@code modelObjectMapper} 是为「调外部模型 API」配的（丢弃 unknown 字段、
 * 序列化时跳过 null），而它顺手把「本应用对外返回的 JSON 长什么样」也接管了。
 * 在加这个类之前，任何新写的、带时间字段的接口都会 500 ——
 * 而错误信息只会让人去查依赖，不会让人想到「是模型层的那个 Bean 干的」。
 *
 * <h2>修法：显式声明 web 层的 primary mapper</h2>
 *
 * <p>{@link Jackson2ObjectMapperBuilder} 是 Spring Boot 自动配置的<b>构建器</b>
 * （它的装配条件是 {@code @ConditionalOnMissingBean(Jackson2ObjectMapperBuilder.class)}，
 * 和 ObjectMapper 那个条件互不影响，所以它一直都在）。
 * 用它 {@code build()} 出来的 mapper 会自动注册 classpath 上所有已知模块，
 * 包括 JavaTimeModule。
 *
 * <p>标 {@code @Primary} 之后：
 * <ul>
 *   <li>Spring MVC 的 JSON 转换器注入到它 → 对外接口的序列化行为恢复成 Boot 默认</li>
 *   <li>{@code client/} 包里的类用的是 {@code @Qualifier("modelObjectMapper")}，
 *       显式限定名，不受 {@code @Primary} 影响 → 模型协议的行为一点没变</li>
 * </ul>
 *
 * <p><b>两套 mapper 的职责，看清楚它们的区别很重要</b>：
 * <table border="1">
 *   <caption>两个 ObjectMapper 的分工</caption>
 *   <tr><th></th><th>webObjectMapper（本类）</th><th>modelObjectMapper</th></tr>
 *   <tr><td>服务对象</td><td>本应用对外的 HTTP 接口</td><td>调用外部模型的 HTTP 请求</td></tr>
 *   <tr><td>未知字段</td><td>报错（早暴露契约变更）</td><td>忽略（模型会加非标准字段）</td></tr>
 *   <tr><td>null 字段</td><td>照常输出（契约要稳定）</td><td>跳过（不传 ≠ 传 null）</td></tr>
 *   <tr><td>时间类型</td><td>JavaTimeModule（ISO-8601 字符串）</td><td>用不到</td></tr>
 * </table>
 *
 * <p>它们<b>本来就该是两个对象</b>，只是 Spring 的按类型注入把两件事搅到了一起。
 */
@Configuration
public class JacksonConfig {

    /**
     * 对外 HTTP 接口的 JSON 序列化器，<b>全局默认</b>。
     *
     * <p>不自己 {@code new ObjectMapper()} 然后手工注册模块，而是用 Boot 的
     * {@link Jackson2ObjectMapperBuilder} —— 后者会自动发现并注册 classpath 上
     * 所有已知的 Jackson 模块（jsr310、parameter-names 等）。
     * 手工注册的话，将来再加一个 Jackson 扩展模块又要来这里补一行，
     * 而 Boot 的自动发现不会有遗漏。
     *
     * <p>参数里的 {@code customizers} 是给将来留的口子：Spring Boot 会收集容器里
     * 所有 {@link Jackson2ObjectMapperBuilderCustomizer} 并在这里应用。
     * 目前项目里一个都没有，但把它作为参数传进来，
     * 意味着以后要调 JSON 行为<B>不必改这个类</B>，
     * 只要新加一个 {@code Jackson2ObjectMapperBuilderCustomizer} Bean 即可。
     */
    @Bean
    @Primary
    public ObjectMapper webObjectMapper(Jackson2ObjectMapperBuilder builder,
                                        java.util.List<Jackson2ObjectMapperBuilderCustomizer> customizers) {
        for (Jackson2ObjectMapperBuilderCustomizer customizer : customizers) {
            customizer.customize(builder);
        }
        return builder.createXmlMapper(false).build();
    }
}
