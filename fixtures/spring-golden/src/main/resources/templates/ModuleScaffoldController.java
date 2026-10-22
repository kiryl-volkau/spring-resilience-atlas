package com.acme.care.scaffold;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/scaffold")
public class ModuleScaffoldController {

    @GetMapping("/health")
    public String health() {
        return "ok";
    }
}
