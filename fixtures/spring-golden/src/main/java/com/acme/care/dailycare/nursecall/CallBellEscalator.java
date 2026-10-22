package com.acme.care.dailycare.nursecall;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

@Component
public class CallBellEscalator {

    private final CallBellService callBells;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();

    public CallBellEscalator(CallBellService callBells) {
        this.callBells = callBells;
    }

    @PostConstruct
    void start() {
        executor.scheduleWithFixedDelay(callBells::escalateUnanswered, 30, 30, TimeUnit.SECONDS);
    }

    @PreDestroy
    void stop() throws InterruptedException {
        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);
    }
}
