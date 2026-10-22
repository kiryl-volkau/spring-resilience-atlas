package com.acme.care;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.ws.config.annotation.EnableWs;

@SpringBootApplication
@EnableScheduling
@EnableAsync
@EnableFeignClients
@EnableWs
public class CareApplication {

    public static void main(String[] args) {
        SpringApplication.run(CareApplication.class, args);
    }
}
