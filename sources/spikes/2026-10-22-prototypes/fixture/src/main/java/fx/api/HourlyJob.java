package fx.api;
import java.lang.annotation.*;
import org.springframework.scheduling.annotation.Scheduled;
@Target(ElementType.METHOD) @Retention(RetentionPolicy.RUNTIME)
@Scheduled(cron = "0 0 * * * *")
public @interface HourlyJob {}
