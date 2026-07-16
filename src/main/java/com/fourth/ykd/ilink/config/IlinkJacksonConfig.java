package com.fourth.ykd.ilink.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class IlinkJacksonConfig {

    @Bean
    public ObjectMapper iLinkObjectMapper() {
        return new ObjectMapper();
    }
}
