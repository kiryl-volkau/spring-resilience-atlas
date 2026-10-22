package fx.web;
import fx.api.*;
import org.springframework.web.bind.annotation.*;
import static fx.api.Paths.Users.BY_ID;
@RestController
public class OrderController implements OrderApi {
    private static final String EXPORT = "/export";
    @Override public String get(long id) { return "x"; }
    @Override public void create(String body) {}
    @Override public String search() { return "s"; }
    @JsonGet(EXPORT) public String export() { return "e"; }
    @GetMapping(BY_ID + "/orders") public String byUser() { return "u"; }           // static import
    @GetMapping("${app.base-path}/health") public String health() { return "ok"; }  // placeholder
    @GetMapping(Unknown.PATH) public String unknown() { return "?"; }               // unresolvable
    @RequestMapping(value = "/multi", method = RequestMethod.GET) // comment with @PostMapping("/fake") inside
    public String multi() { return "m"; }
    /* block comment @GetMapping("/alsofake") */
    public String notMapped() { return "n"; }
    @RestController
    @RequestMapping("/nested")
    public static class Nested { @GetMapping("/inner") public String inner() { return "i"; } }
}
