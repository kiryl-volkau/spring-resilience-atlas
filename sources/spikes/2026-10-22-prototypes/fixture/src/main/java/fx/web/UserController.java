package fx.web;
import fx.api.Paths;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping({Paths.Users.BASE, "/legacy/users"})
public class UserController {
    @GetMapping({Paths.Users.BY_ID, "/me"}) public String get() { return "g"; }
    @DeleteMapping(path = Paths.Users.BY_ID) public void del() {}
}
