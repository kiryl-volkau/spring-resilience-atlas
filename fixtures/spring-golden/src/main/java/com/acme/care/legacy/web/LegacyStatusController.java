package com.acme.care.legacy.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
public class LegacyStatusController {

    @RequestMapping
    @ResponseBody
    public String index() {
        return "care-platform legacy front door";
    }

    @GetMapping("/legacy/login")
    public String loginPage() {
        return "legacy/login";
    }
}
