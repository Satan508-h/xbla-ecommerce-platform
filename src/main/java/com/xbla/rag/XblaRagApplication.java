package com.xbla.rag;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

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
public class XblaRagApplication {

    public static void main(String[] args) {
        SpringApplication.run(XblaRagApplication.class, args);
    }
}
