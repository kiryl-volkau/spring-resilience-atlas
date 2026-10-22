package com.acme.care.dailycare.graphql;

import java.util.List;
import java.util.UUID;

import com.acme.care.dailycare.shift.ShiftQueryService;
import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsData;
import com.netflix.graphql.dgs.DgsDataFetchingEnvironment;
import com.netflix.graphql.dgs.DgsQuery;
import com.netflix.graphql.dgs.InputArgument;

@DgsComponent
public class ShiftDataFetcher {

    public record ShiftView(UUID id, String unit, UUID nurseId) {
    }

    public record NurseView(UUID id, String name) {
    }

    private final ShiftQueryService shifts;

    public ShiftDataFetcher(ShiftQueryService shifts) {
        this.shifts = shifts;
    }

    @DgsQuery(field = "openShifts")
    public List<ShiftView> shifts(@InputArgument String unit) {
        return shifts.open(unit);
    }

    @DgsData(parentType = "Shift", field = "assignedNurse")
    public NurseView nurse(DgsDataFetchingEnvironment env) {
        ShiftView shift = env.getSource();
        return shifts.nurse(shift.nurseId());
    }
}
