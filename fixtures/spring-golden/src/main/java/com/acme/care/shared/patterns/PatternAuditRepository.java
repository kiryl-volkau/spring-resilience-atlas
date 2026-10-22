package com.acme.care.shared.patterns;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PatternAuditRepository extends JpaRepository<PatternAudit, Long> {
}
