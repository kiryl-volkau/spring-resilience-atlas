package com.acme.care.resident.messaging;

import com.acme.care.resident.service.TransferService;
import com.acme.care.resident.service.TransferService.TransferRequest;
import io.awspring.cloud.sqs.annotation.SqsListener;
import org.springframework.stereotype.Component;

@Component
public class ResidentTransferListener {

    private final TransferService transferService;

    public ResidentTransferListener(TransferService transferService) {
        this.transferService = transferService;
    }

    @SqsListener(queueNames = "${care.resident.transfer-queue}", id = "resident-transfers", maxConcurrentMessages = "4")
    public void onTransferRequest(TransferRequest request) {
        transferService.apply(request);
    }
}
