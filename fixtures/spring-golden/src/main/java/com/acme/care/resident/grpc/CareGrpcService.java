package com.acme.care.resident.grpc;

import java.util.UUID;

import com.acme.care.dailycare.vitals.VitalsStreamService;
import com.acme.care.grpc.CareGrpc;
import com.acme.care.grpc.ResidentReply;
import com.acme.care.grpc.ResidentRequest;
import com.acme.care.grpc.VitalsAck;
import com.acme.care.grpc.VitalsReading;
import com.acme.care.resident.service.ResidentQueryService;
import com.acme.care.resident.service.ResidentSummary;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;

@GrpcService
public class CareGrpcService extends CareGrpc.CareImplBase {

    private final ResidentQueryService residents;
    private final VitalsStreamService vitalsStreams;

    public CareGrpcService(ResidentQueryService residents, VitalsStreamService vitalsStreams) {
        this.residents = residents;
        this.vitalsStreams = vitalsStreams;
    }

    @Override
    public void getResident(ResidentRequest request, StreamObserver<ResidentReply> responseObserver) {
        try {
            ResidentSummary summary = residents.summary(UUID.fromString(request.getResidentId()));
            responseObserver.onNext(toReply(summary));
            responseObserver.onCompleted();
        } catch (IllegalArgumentException e) {
            fail(responseObserver, e);
        }
    }

    @Override
    public StreamObserver<VitalsReading> streamVitals(StreamObserver<VitalsAck> responseObserver) {
        return vitalsStreams.open(responseObserver);
    }

    public ResidentReply toReply(ResidentSummary summary) {
        return ResidentReply.newBuilder()
                .setResidentId(summary.residentId().toString())
                .setCareLevel(summary.careLevel())
                .build();
    }

    private static void fail(StreamObserver<?> observer, Throwable error) {
        observer.onError(Status.INVALID_ARGUMENT.withDescription(error.getMessage()).asRuntimeException());
    }
}
