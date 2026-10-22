package com.acme.care.resident.web;

import java.util.UUID;

import com.acme.care.resident.api.generated.ResidentAdmissionsApi;
import com.acme.care.resident.api.generated.model.AdmissionDto;
import com.acme.care.resident.api.generated.model.AdmissionRequestDto;
import com.acme.care.resident.service.AdmissionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ResidentAdmissionController implements ResidentAdmissionsApi, ResidentPaths {

    private final AdmissionService admissionService;

    public ResidentAdmissionController(AdmissionService admissionService) {
        this.admissionService = admissionService;
    }

    @Override
    public ResponseEntity<AdmissionDto> admitResident(AdmissionRequestDto admissionRequestDto) {
        AdmissionDto admission = admissionService.admit(admissionRequestDto);
        return ResponseEntity.status(HttpStatus.CREATED).body(admission);
    }

    @Override
    public ResponseEntity<AdmissionDto> getAdmission(UUID admissionId) {
        return ResponseEntity.of(admissionService.find(admissionId));
    }

    @GetMapping(BASE + "/admissions/pending-count")
    public long pendingCount() {
        return admissionService.countPending();
    }
}
