package com.acme.care.resident.graphql;

import java.util.List;
import java.util.UUID;

import com.acme.care.resident.service.ResidentGraphService;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.stereotype.Controller;

@Controller
public class ResidentGraphqlController {

    public record ResidentView(UUID id, String name, String careLevel) {
    }

    public record AdmitResidentInput(String name, String careLevel, UUID roomId) {
    }

    public record CarePlanView(UUID id, String summary) {
    }

    public record MedicationView(String name, String dose) {
    }

    private final ResidentGraphService residents;

    public ResidentGraphqlController(ResidentGraphService residents) {
        this.residents = residents;
    }

    @QueryMapping
    public ResidentView resident(@Argument UUID id) {
        return residents.find(id);
    }

    @MutationMapping(name = "admitResident")
    public ResidentView admit(@Argument AdmitResidentInput input) {
        return residents.admit(input);
    }

    @SchemaMapping(typeName = "Resident", field = "carePlan")
    public CarePlanView carePlanOf(ResidentView resident) {
        return residents.carePlanOf(resident.id());
    }

    @SchemaMapping
    public List<MedicationView> medications(ResidentView resident) {
        return residents.medicationsOf(resident.id());
    }
}
