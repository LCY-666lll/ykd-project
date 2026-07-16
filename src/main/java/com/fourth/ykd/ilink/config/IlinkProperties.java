package com.fourth.ykd.ilink.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "ilink")
public class IlinkProperties {

    private boolean enabled;

    private String sessionFile = ".ilink/session.token";
}
