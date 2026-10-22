package com.acme.care.resident.soap;

import com.acme.care.resident.service.MovementService;
import com.acme.care.resident.soap.schema.DischargeAcknowledgement;
import com.acme.care.resident.soap.schema.DischargeNotice;
import com.acme.care.resident.soap.schema.TransferAcknowledgement;
import com.acme.care.resident.soap.schema.TransferNotice;
import org.springframework.ws.server.endpoint.annotation.Endpoint;
import org.springframework.ws.server.endpoint.annotation.RequestPayload;
import org.springframework.ws.server.endpoint.annotation.ResponsePayload;
import org.springframework.ws.soap.addressing.server.annotation.Action;
import org.springframework.ws.soap.server.endpoint.annotation.SoapAction;

@Endpoint
public class ResidentMovementEndpoint {

    private final MovementService movements;

    public ResidentMovementEndpoint(MovementService movements) {
        this.movements = movements;
    }

    @SoapAction("urn:care/Discharge")
    @ResponsePayload
    public DischargeAcknowledgement discharge(@RequestPayload DischargeNotice notice) {
        return movements.recordDischarge(notice);
    }

    @Action("urn:care/Transfer")
    @ResponsePayload
    public TransferAcknowledgement transfer(@RequestPayload TransferNotice notice) {
        return movements.recordTransfer(notice);
    }
}
