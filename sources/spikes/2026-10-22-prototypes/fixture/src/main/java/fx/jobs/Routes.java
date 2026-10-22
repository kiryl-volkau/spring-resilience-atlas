package fx.jobs;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.function.*;
import static org.springframework.web.servlet.function.RouterFunctions.route;
import org.quartz.Job; import org.quartz.JobExecutionContext;
public class Routes {
    @Bean public RouterFunction<ServerResponse> routes() { return route().GET("/fn/a", r -> null).POST("/fn/b", r -> null).build(); }
    public static class Q implements Job { public void execute(JobExecutionContext c) {} }
}
