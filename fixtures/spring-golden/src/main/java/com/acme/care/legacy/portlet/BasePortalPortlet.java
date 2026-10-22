package com.acme.care.legacy.portlet;

import org.springframework.web.portlet.bind.annotation.ResourceMapping;

import javax.portlet.ResourceRequest;
import javax.portlet.ResourceResponse;

public abstract class BasePortalPortlet {

    @ResourceMapping("ping")
    public void ping(ResourceRequest request, ResourceResponse response) {
        response.setContentType("text/plain");
    }
}
