package fx.api;
import org.springframework.web.bind.annotation.*;
@RequestMapping(Paths.Orders.BASE)
public interface OrderApi {
    @GetMapping(Paths.Orders.BY_ID) String get(@PathVariable long id);
    @PostMapping void create(@RequestBody String body);
    @RequestMapping(path = {"/search", "/find"}, method = {RequestMethod.GET, RequestMethod.POST}) String search();
}
