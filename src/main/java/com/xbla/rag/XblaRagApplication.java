package com.xbla.rag;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 休伯利安（XBLA）电商导购与售后 RAG 平台 —— 启动类。
 *
 * <p>{@code @SpringBootApplication} 是一个「三合一」注解，等价于下面三个：
 * <ul>
 *   <li>{@code @SpringBootConfiguration} —— 声明这是一个配置类</li>
 *   <li>{@code @EnableAutoConfiguration} —— 开启自动配置。
 *       这是 Spring Boot 最核心的魔法：它扫描 classpath 上存在的依赖，
 *       自动帮你把该配的东西配好。比如引入了 spring-boot-starter-web，
 *       它就自动配好 Tomcat 和 Spring MVC，不用写一行 XML。</li>
 *   <li>{@code @ComponentScan} —— 扫描<b>当前包及其子包</b>下所有带
 *       {@code @Component} / {@code @Service} / {@code @RestController} 等
 *       注解的类，注册成 Spring Bean。</li>
 * </ul>
 *
 * <p><b>为什么这个类必须放在最外层的 {@code com.xbla.rag} 包下？</b>
 * 因为 {@code @ComponentScan} 默认只扫描<b>启动类所在包及子包</b>。
 * 这个类在 {@code com.xbla.rag}，所以 {@code com.xbla.rag.controller}、
 * {@code com.xbla.rag.service} 等所有子包都会被扫到。
 * 如果把它挪到 {@code com.xbla.rag.config} 下，那 controller 和 service
 * 就扫不到了，接口会全部 404 —— 这是新手最常踩的坑之一。
 */
@SpringBootApplication
/*
 * @MapperScan 告诉 MyBatis：这个包下的接口都是 Mapper，启动时为它们生成代理实现。
 *
 * 【为什么需要它】
 * @ComponentScan 只认 @Component / @Service / @RestController 这些注解，
 * 而 Mapper 是「接口」——接口没法被实例化，也不会被注册成 Bean。
 * MyBatis 的做法是在启动时为每个 Mapper 接口动态生成一个代理类，
 * 由代理去执行 SQL。@MapperScan 就是触发这个动作的开关。
 *
 * 【不加会怎样】
 * 报错：Consider defining a bean of type 'com.xbla.rag.mapper.ProductMapper'
 *      in your configuration.
 *
 * 【另一种写法】
 * 也可以在每个 Mapper 接口上单独加 @Mapper 注解。
 * 但 17 个接口就要写 17 次，用 @MapperScan 一次搞定，改包名时也只改一处。
 */
@MapperScan("com.xbla.rag.mapper")
/*
 * @ConfigurationPropertiesScan —— 扫描并注册 @ConfigurationProperties 类（阶段 2 新增）。
 *
 * 【为什么需要它】
 * @ComponentScan 不认 @ConfigurationProperties —— 这个注解本身只是「声明配置前缀」，
 * 不带任何 Bean 注册语义。所以在它被发现之前，配置类必须先带 @Component。
 *
 * 有两种解法：
 *   A. 每个配置类上写 @Component
 *   B. 启动类上写一次 @ConfigurationPropertiesScan
 *
 * 选 B 的理由：配置类不用带任何 Spring 注解，职责更单纯 ——
 * 它就是个「装着配置字段的普通 POJO」，不该关心自己怎么被注册。
 *
 * 【扫描范围】
 * 扫的是启动类所在包（com.xbla.rag）及子包，所以 config/ 下的
 * LlmProperties 会被扫到。将来配置类变多了也不用回来改这里。
 */
@ConfigurationPropertiesScan
/*
 * @EnableScheduling —— 开启 @Scheduled 支持（阶段 6 新增，本项目第一次用定时任务）。
 *
 * 【它做什么】
 * 注册一个 ScheduledAnnotationBeanPostProcessor，它会去扫所有带 @Scheduled 的方法，
 * 以及所有实现了 SchedulingConfigurer 的 Bean，把它们排进一个 TaskScheduler。
 *
 * 【为什么不挂在 ratelimit 的配置类上】
 * 它是一个**平台能力**，不是某个模块的私事 —— 和上面的 @MapperScan /
 * @ConfigurationPropertiesScan 同一性质。挂在 xbla.ratelimit.enabled 下面的话，
 * 将来任何一个模块想加定时任务，都得先知道「原来调度是被限流模块开着的」，
 * 而那个关联没有任何道理。
 *
 * 【谁真的在用它】
 * 目前只有 QueueHeartbeat（看门狗续期 + 僵尸清扫），它自己带
 * @ConditionalOnProperty，所以 xbla.ratelimit.enabled=false 时
 * 这个开关还在、但一个任务都不会注册，调度器线程也不会被创建。
 */
@EnableScheduling
public class XblaRagApplication {

    public static void main(String[] args) {
        SpringApplication.run(XblaRagApplication.class, args);
    }
}
