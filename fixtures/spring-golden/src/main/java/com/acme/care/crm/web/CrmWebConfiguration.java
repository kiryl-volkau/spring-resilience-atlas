package com.acme.care.crm.web;

import static com.acme.care.shared.api.ApiPaths.Crm.*;

import com.acme.care.crm.service.ContactSearchIndexer;
import com.acme.care.crm.service.ContactSearchIndexer.IndexStatus;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class CrmWebConfiguration {

    static final String REINDEX = "/search-index/rebuild";

    @Bean
    public WebMvcConfigurer crmCorsConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping(BASE + "/**").allowedOrigins("https://crm.acme-care.example");
            }
        };
    }

    @RestController
    @RequestMapping(ADMIN)
    static class CrmMaintenanceController {

        private final ContactSearchIndexer indexer;

        CrmMaintenanceController(ContactSearchIndexer indexer) {
            this.indexer = indexer;
        }

        @PostMapping(REINDEX)
        public ResponseEntity<Void> rebuildSearchIndex() {
            indexer.rebuildAsync();
            return ResponseEntity.accepted().build();
        }

        @GetMapping("/search-index/status")
        public IndexStatus indexStatus() {
            return indexer.status();
        }
    }
}
