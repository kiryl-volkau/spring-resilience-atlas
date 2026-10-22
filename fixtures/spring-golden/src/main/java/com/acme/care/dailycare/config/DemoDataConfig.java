package com.acme.care.dailycare.config;

import com.acme.care.dailycare.wards.DemoWards;
import com.acme.care.dailycare.wards.WardRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("demo")
public class DemoDataConfig {

    @Bean
    public CommandLineRunner seedDemoWard(WardRepository wards) {
        return args -> {
            if (wards.count() == 0) {
                wards.saveAll(DemoWards.sample());
            }
        };
    }
}
