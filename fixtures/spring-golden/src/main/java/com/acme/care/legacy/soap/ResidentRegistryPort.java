package com.acme.care.legacy.soap;

import jakarta.jws.WebMethod;
import jakarta.jws.WebParam;
import jakarta.jws.WebService;

@WebService(name = "ResidentRegistry", targetNamespace = "http://legacy.care.acme.com/registry")
public interface ResidentRegistryPort {

    class ResidentRecord {
        public String nhsNumber;
        public String fullName;
        public String dateOfBirth;
        public String homeCode;
    }

    @WebMethod
    ResidentRecord findResident(@WebParam(name = "nhsNumber") String nhsNumber);

    @WebMethod
    String registerResident(@WebParam(name = "resident") ResidentRecord resident);
}
