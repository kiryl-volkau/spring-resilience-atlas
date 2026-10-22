package com.acme.care.resident.web;

import com.acme.care.shared.api.ApiPaths;

public interface ResidentPaths {

    String BASE = ApiPaths.API + "/residents";
    String BY_ID = BASE + "/{residentId}";
}
