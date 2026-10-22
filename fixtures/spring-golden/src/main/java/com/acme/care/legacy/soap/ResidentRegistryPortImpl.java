package com.acme.care.legacy.soap;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.acme.care.legacy.registry.RegistryGateway;
import jakarta.jws.WebService;

@WebService(endpointInterface = "com.acme.care.legacy.soap.ResidentRegistryPort",
        serviceName = "ResidentRegistryService",
        targetNamespace = "http://legacy.care.acme.com/registry")
public class ResidentRegistryPortImpl implements ResidentRegistryPort {

    private final RegistryGateway gateway;
    private final Map<String, ResidentRecord> cache = new ConcurrentHashMap<>();

    public ResidentRegistryPortImpl(RegistryGateway gateway) {
        this.gateway = gateway;
    }

    @Override
    public ResidentRecord findResident(String nhsNumber) {
        return cache.computeIfAbsent(nhsNumber, gateway::lookup);
    }

    @Override
    public String registerResident(ResidentRecord resident) {
        String registryId = gateway.register(resident);
        cache.put(resident.nhsNumber, resident);
        return registryId;
    }

    public void evictCache() {
        cache.clear();
    }
}
