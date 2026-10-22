package com.acme.care.legacy.soap;

import com.acme.care.legacy.billing.LegacyLedger;
import com.acme.care.legacy.soap.types.AdjustmentRequest;
import com.acme.care.legacy.soap.types.AdjustmentResponse;
import com.acme.care.legacy.soap.types.StatementRequest;
import com.acme.care.legacy.soap.types.StatementResponse;
import jakarta.jws.WebMethod;
import jakarta.jws.WebParam;
import jakarta.jws.WebService;

@WebService(serviceName = "LegacyBillingService", targetNamespace = "http://legacy.care.acme.com/billing")
public class LegacyBillingSoapService {

    private final LegacyLedger ledger;

    public LegacyBillingSoapService(LegacyLedger ledger) {
        this.ledger = ledger;
    }

    @WebMethod(operationName = "GetStatement")
    public StatementResponse getStatement(@WebParam(name = "request") StatementRequest request) {
        validateAccount(request.getAccountNumber());
        return ledger.statementFor(request.getAccountNumber(), request.getPeriod());
    }

    @WebMethod(operationName = "PostAdjustment")
    public AdjustmentResponse postAdjustment(@WebParam(name = "request") AdjustmentRequest request) {
        validateAccount(request.getAccountNumber());
        return ledger.adjust(request.getAccountNumber(), request.getAmount(), request.getReason());
    }

    @WebMethod(exclude = true)
    public void reloadTariffCache() {
        ledger.reloadTariffs();
    }

    public static String contractVersion() {
        return "2014-03";
    }

    private void validateAccount(String accountNumber) {
        if (accountNumber == null || !accountNumber.matches("[A-Z]{2}\\d{8}")) {
            throw new IllegalArgumentException("Invalid legacy account number: " + accountNumber);
        }
    }
}
