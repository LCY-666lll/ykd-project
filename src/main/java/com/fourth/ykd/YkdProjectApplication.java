package com.fourth.ykd;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@Slf4j
public class YkdProjectApplication {

    public static void main(String[] args) {

        SpringApplication.run(YkdProjectApplication.class, args);

        log.info("==========");
        log.info("Spring Boot 启动成功！");
        log.info("==========");
    }
}