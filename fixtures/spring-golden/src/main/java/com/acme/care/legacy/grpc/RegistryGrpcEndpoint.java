package com.acme.care.legacy.grpc;

import com.acme.care.legacy.registry.RegistryGateway;
import com.acme.care.legacy.soap.ResidentRegistryPort.ResidentRecord;
import com.acme.care.registry.grpc.LookupReply;
import com.acme.care.registry.grpc.LookupRequest;
import com.acme.care.registry.grpc.RegistryGrpc;
import io.grpc.stub.StreamObserver;
import org.springframework.stereotype.Component;

@Component
public class RegistryGrpcEndpoint extends RegistryGrpc.RegistryImplBase {

    private final RegistryGateway gateway;

    public RegistryGrpcEndpoint(RegistryGateway gateway) {
        this.gateway = gateway;
    }

    @Override
    public void lookupResident(LookupRequest request, StreamObserver<LookupReply> responseObserver) {
        ResidentRecord record = gateway.lookup(request.getNhsNumber());
        responseObserver.onNext(LookupReply.newBuilder()
                .setFullName(record.fullName)
                .setHomeCode(record.homeCode)
                .build());
        responseObserver.onCompleted();
    }
}
