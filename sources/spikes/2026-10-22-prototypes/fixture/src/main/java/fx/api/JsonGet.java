package fx.api;
import java.lang.annotation.*;
import org.springframework.core.annotation.AliasFor;
import org.springframework.web.bind.annotation.*;
@Target(ElementType.METHOD) @Retention(RetentionPolicy.RUNTIME)
@RequestMapping(method = RequestMethod.GET, produces = "application/json")
public @interface JsonGet { @AliasFor(annotation = RequestMapping.class) String[] value() default {}; }
