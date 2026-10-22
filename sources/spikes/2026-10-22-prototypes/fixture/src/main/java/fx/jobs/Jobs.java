package fx.jobs;
import fx.api.HourlyJob;
import org.springframework.scheduling.annotation.*;
import org.springframework.context.event.EventListener;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.kafka.annotation.KafkaListener;
import io.awspring.cloud.sqs.annotation.SqsListener;
import java.util.function.Consumer;
import org.springframework.context.annotation.Bean;
import org.springframework.messaging.Message;
@Async
public class Jobs {
    static final String TOPIC = "orders." + "created";
    @Scheduled(fixedDelayString = "${jobs.delay:5000}", initialDelay = 1000) public void poll() {}
    @Scheduled(cron = "${jobs.cron}") public void cron() {}
    @Schedules({@Scheduled(fixedRate = 10), @Scheduled(cron = "0 * * * * *")}) public void both() {}
    @HourlyJob public void hourly() {}
    @KafkaListener(topics = {TOPIC, "orders.updated"}, groupId = "g1") public void onOrder(String s) {}
    @SqsListener("${sqs.queue}") public void onSqs(String s) {}
    @EventListener public void onEvt(Object e) {}
    @EventListener(classes = {String.class}) public void onEvt2(Object e) {}
    @TransactionalEventListener(phase = org.springframework.transaction.event.TransactionPhase.AFTER_COMMIT) public void onCommit(Object e) {}
    @Async("pool") public void asyncMethod() {}
    @Bean public Consumer<Message<String>> consume() { return m -> {}; }
}
