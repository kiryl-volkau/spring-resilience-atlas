package com.acme.care.shared.startup;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

public abstract class AbstractStartupTask implements ApplicationRunner {

    private final Logger log = LoggerFactory.getLogger(getClass());

    @Override
    public void run(ApplicationArguments args) throws Exception {
        long started = System.nanoTime();
        execute();
        log.info("Startup task {} finished in {} ms",
                getClass().getSimpleName(), (System.nanoTime() - started) / 1_000_000);
    }

    protected abstract void execute() throws Exception;
}
