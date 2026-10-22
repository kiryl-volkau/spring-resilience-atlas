package com.acme.care.resident.web;

import java.util.List;

import com.acme.care.resident.service.CareLevelRepository;
import com.acme.care.resident.service.CareLevelView;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/care-levels")
public class CareLevelController implements InitializingBean {

    private final CareLevelRepository careLevels;
    private volatile List<CareLevelView> catalog = List.of();

    public CareLevelController(CareLevelRepository careLevels) {
        this.careLevels = careLevels;
    }

    @Override
    public void afterPropertiesSet() {
        catalog = careLevels.findAllActive();
    }

    @GetMapping
    public List<CareLevelView> list() {
        return catalog;
    }
}
