package com.acme.care.dailycare.soap;

import com.acme.care.dailycare.careplan.CarePlanService;
import com.acme.care.dailycare.soap.schema.GetCarePlanRequest;
import com.acme.care.dailycare.soap.schema.GetCarePlanResponse;
import com.acme.care.dailycare.soap.schema.UpdateCarePlanRequest;
import com.acme.care.dailycare.soap.schema.UpdateCarePlanResponse;
import org.springframework.ws.server.endpoint.annotation.Endpoint;
import org.springframework.ws.server.endpoint.annotation.PayloadRoot;
import org.springframework.ws.server.endpoint.annotation.RequestPayload;
import org.springframework.ws.server.endpoint.annotation.ResponsePayload;

@Endpoint
public class CarePlanEndpoint {

    private static final String NAMESPACE_URI = "http://acme.com/care/dailycare/careplan";

    private final CarePlanService carePlanService;

    public CarePlanEndpoint(CarePlanService carePlanService) {
        this.carePlanService = carePlanService;
    }

    @PayloadRoot(namespace = NAMESPACE_URI, localPart = "GetCarePlanRequest")
    @ResponsePayload
    public GetCarePlanResponse getCarePlan(@RequestPayload GetCarePlanRequest request) {
        return carePlanService.toResponse(carePlanService.current(request.getResidentId()));
    }

    @PayloadRoot(namespace = NAMESPACE_URI, localPart = "UpdateCarePlanRequest")
    @ResponsePayload
    public UpdateCarePlanResponse updateCarePlan(@RequestPayload UpdateCarePlanRequest request) {
        return carePlanService.applyCommissionerUpdate(request);
    }
}
