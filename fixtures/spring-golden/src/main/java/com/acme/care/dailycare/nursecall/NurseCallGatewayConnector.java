package com.acme.care.dailycare.nursecall;

import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

@Component
public class NurseCallGatewayConnector implements SmartLifecycle {

    private final NurseCallGateway gateway;
    private final AtomicBoolean running = new AtomicBoolean();

    public NurseCallGatewayConnector(NurseCallGateway gateway) {
        this.gateway = gateway;
    }

    @Override
    public void start() {
        gateway.connect();
        running.set(true);
    }

    @Override
    public void stop() {
        running.set(false);
        gateway.disconnect();
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 100;
    }
}
