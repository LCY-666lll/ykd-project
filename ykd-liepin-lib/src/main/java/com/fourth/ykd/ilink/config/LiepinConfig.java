package com.fourth.ykd.ilink.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(LiepinProperties.class)
public class LiepinConfig {
}
//---
//  一句话概括
//
//  LiepinConfig 是一个"空壳配置类"，它的唯一作用就是激活 LiepinProperties。
//
//  ---
//  两个注解的作用
//  @Configuration
//
//  作用：标记这是一个 Spring 配置类
//
//  含义：
//  - 告诉 Spring 这个类包含 Bean 定义
//  - 虽然类体是空的，但仍然是一个配置类
//
//  ---
//  2️⃣@EnableConfigurationProperties
//
//  @EnableConfigurationProperties(LiepinProperties.class)
//
//  作用：激活 LiepinProperties 的配置绑定功能
//
//  做了两件事：
//  1. 把 LiepinProperties 注册为 Spring Bean
//  2. 启用 @ConfigurationProperties(prefix = "liepin") 绑定
//
//  ---
//  为什么需要这个类？
//
//  看 LiepinProperties 的定义：
//
//  @Getter
//  @Setter
//  @ConfigurationProperties(prefix = "liepin")  // ← 声明了配置前缀
//  public class LiepinProperties {
//      private String cookie = "";
//      private boolean headless = false;
//      // ...
//  }
//
//  问题：光有 @ConfigurationProperties 注解不够，还需要有人"激活"它
//
//  解决方案：用 @EnableConfigurationProperties 激活
//
//  ---
//  配置绑定流程
//
//  application.properties
//      │
//      │  liepin.cookie=xxx
//      │  liepin.headless=false
//      │  liepin.timeout-ms=15000
//      │
//      ▼
//  ┌─────────────────────────────────────┐
//  │  @EnableConfigurationProperties     │  ← LiepinConfig 激活
//  │         (LiepinProperties.class)    │
//  └─────────────────────────────────────┘
//      │
//      ▼
//  ┌─────────────────────────────────────┐
//  │  @ConfigurationProperties           │  ← LiepinProperties 接收
//  │  (prefix = "liepin")                │
//  │                                     │
//  │  private String cookie;             │  ← 绑定 liepin.cookie
//  │  private boolean headless;          │  ← 绑定 liepin.headless
//  │  private long timeoutMs;            │  ← 绑定 liepin.timeout-ms
//  └─────────────────────────────────────┘
//      │
//      ▼
//  ┌─────────────────────────────────────┐
//  │  LiepinProperties (Spring Bean)     │
//  │                                     │
//  │  getCookie() → "xxx"                │
//  │  isHeadless() → false               │
//  │  getTimeoutMs() → 15000             │
//  └─────────────────────────────────────┘
//
//  ---
//  两种激活方式对比
//
//  ┌──────────────────────────┬────────────────────────────────────────────────────────┬──────────────────┐
//  │           方式           │                          代码                          │       位置       │
//  ├──────────────────────────┼────────────────────────────────────────────────────────┼──────────────────┤
//  │ 方式1：在配置类上激活 ✅  │ @EnableConfigurationProperties(LiepinProperties.class) │ LiepinConfig     │
//  ├──────────────────────────┼────────────────────────────────────────────────────────┼──────────────────┤
//  │ 方式2：在属性类上激活    │ @Component                                             │ LiepinProperties │
//  └──────────────────────────┴────────────────────────────────────────────────────────┴──────────────────┘
//
//  本项目用的是方式1，好处是：
//  - 配置类和属性类职责分离
//  - 配置类可以集中管理多个属性类
//
//  ---
//  为什么类体是空的？
//
//  @Configuration
//  @EnableConfigurationProperties(LiepinProperties.class)
//  public class LiepinConfig {
//      // 空的！
//  }
//
//  因为：
//  - 这个类不需要定义其他 Bean
//  - 它的唯一职责就是激活 LiepinProperties
//  - 如果以后需要定义其他 Bean，可以加在这里
//
//  如果需要扩展：
//  @Configuration
//  @EnableConfigurationProperties(LiepinProperties.class)
//  public class LiepinConfig {
//
//      // 未来可以添加其他 Bean
//      @Bean
//      public SomeService someService(LiepinProperties properties) {
//          return new SomeService(properties);
//      }
//  }
//
//  ---
//  与 LiepinProperties 的关系
//
//  ┌──────────────────┬──────────────┐
//  │        类        │     职责     │
//  ├──────────────────┼──────────────┤
//  │ LiepinConfig     │ 激活配置绑定 │
//  ├──────────────────┼──────────────┤
//  │ LiepinProperties │ 存储配置值   │
//  └──────────────────┴──────────────┘
//
//  协作：
//  LiepinConfig 激活 → LiepinProperties 生效 → LiepinClient 使用
//
//  ---
//  总结
//
//  ┌──────────┬────────────────────────────────────────┐
//  │   要点   │                  说明                  │
//  ├──────────┼────────────────────────────────────────┤
//  │ 类的作用 │ 激活 LiepinProperties                  │
//  ├──────────┼────────────────────────────────────────┤
//  │ 核心注解 │ @EnableConfigurationProperties         │
//  ├──────────┼────────────────────────────────────────┤
//  │ 类体     │ 空的（只有注解）                       │
//  ├──────────┼────────────────────────────────────────┤
//  │ 必要性   │ 必须有，否则 LiepinProperties 不会生效 │
//  └──────────┴────────────────────────────────────────┘
//
//  一句话记忆：LiepinConfig 是 LiepinProperties 的"开关"，不开这个类，配置读不进来。