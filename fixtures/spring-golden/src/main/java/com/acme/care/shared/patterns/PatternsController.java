package com.acme.care.shared.patterns;

import com.google.common.base.Joiner;
import org.springframework.core.task.TaskExecutor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Analyzer regression cases: each endpoint isolates one way a hand-off or a transaction can be misread. */
@RestController
@RequestMapping("/api/patterns")
public class PatternsController {

    private final PatternAuditService auditService;
    private final ResidentNotifier notifier;
    private final TransactionTemplate transactionTemplate;
    private final PatternAuditRepository auditRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final TaskExecutor executor;

    public PatternsController(PatternAuditService auditService, ResidentNotifier notifier,
                              TransactionTemplate transactionTemplate, PatternAuditRepository auditRepository,
                              KafkaTemplate<String, String> kafkaTemplate, TaskExecutor executor) {
        this.auditService = auditService;
        this.notifier = notifier;
        this.transactionTemplate = transactionTemplate;
        this.auditRepository = auditRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.executor = executor;
    }

    @PostMapping("/after-commit")
    public void afterCommit() {
        auditService.recordAndPublishAfterCommit("after-commit");
    }

    @PostMapping("/tx-template")
    public void txTemplate() {
        transactionTemplate.executeWithoutResult(status -> {
            auditRepository.save(new PatternAudit("tx-template"));
            kafkaTemplate.send("pattern-audit", "tx-template");
        });
    }

    @PostMapping("/overload")
    public void overload() {
        notifier.notifyResident(new ResidentNoticeDto("resident-1"));
    }

    @GetMapping("/parallel")
    public String parallel() {
        return CompletableFuture.supplyAsync(() -> "a").join();
    }

    @PostMapping("/wrapped")
    public Mono<String> wrapped() {
        CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> "a", executor);
        return Mono.fromFuture(future);
    }

    @GetMapping("/stream")
    public SseEmitter stream() {
        SseEmitter emitter = new SseEmitter();
        executor.execute(emitter::complete);
        return emitter;
    }

    @PostMapping("/pumped")
    public void pumped() {
        Thread worker = pump("pumped");
        join(worker);
    }

    private Thread pump(String id) {
        return Thread.ofVirtual().start(() -> auditService.recordAndPublishAfterCommit(id));
    }

    private static void join(Thread thread) {
        try {
            thread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @PostMapping("/fire")
    public void fire(List<String> ids) {
        String joined = Joiner.on(",").join(ids);
        executor.execute(() -> auditService.recordAndPublishAfterCommit(joined));
    }
}
