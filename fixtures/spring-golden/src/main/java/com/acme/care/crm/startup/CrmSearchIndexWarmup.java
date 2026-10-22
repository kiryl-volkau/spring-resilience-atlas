package com.acme.care.crm.startup;

import com.acme.care.crm.service.ContactSearchIndexer;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class CrmSearchIndexWarmup implements ApplicationRunner {

    private final ContactSearchIndexer indexer;

    public CrmSearchIndexWarmup(ContactSearchIndexer indexer) {
        this.indexer = indexer;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!args.containsOption("skip-index-warmup")) {
            indexer.warmUp();
        }
    }
}
