package com.linklike.server;

import com.linklike.server.config.ServerProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Link! Like! Love Live! 5.1.0 本地服务端。
 *
 * <p>只支持 5.1.0 协议。HTTP 层（{@code /v1/**}）与数据层分离：协议字段名由
 * {@code wire_models.json} 驱动，玩家状态存在 MariaDB 里。
 */
@SpringBootApplication
@EnableConfigurationProperties(ServerProperties.class)
public class LinkLikeServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(LinkLikeServerApplication.class, args);
    }
}
