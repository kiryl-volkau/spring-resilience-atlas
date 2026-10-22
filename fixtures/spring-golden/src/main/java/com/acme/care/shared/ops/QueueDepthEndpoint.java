package com.acme.care.shared.ops;

import java.util.Map;

import org.springframework.boot.actuate.endpoint.annotation.DeleteOperation;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.stereotype.Component;

@Component
@Endpoint(id = "queues")
public class QueueDepthEndpoint {

    private final QueueMetrics metrics;

    public QueueDepthEndpoint(QueueMetrics metrics) {
        this.metrics = metrics;
    }

    @ReadOperation
    public Map<String, Long> depths() {
        return metrics.depthByQueue();
    }

    @WriteOperation
    public void pause(@Selector String queue, boolean paused) {
        metrics.setConsumerPaused(queue, paused);
    }

    @DeleteOperation
    public void resetDeadLetterCount(@Selector String queue) {
        metrics.resetDeadLetterCount(queue);
    }
}
