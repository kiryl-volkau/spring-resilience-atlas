package com.acme.care.shared.patterns;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;

@Entity
public class PatternAudit {

    @Id
    @GeneratedValue
    private Long id;

    private String name;

    protected PatternAudit() {
    }

    public PatternAudit(String name) {
        this.name = name;
    }
}
