package com.acme.care.legacy.camel;

import com.acme.care.legacy.lab.LabResultImporter;
import org.apache.camel.builder.RouteBuilder;
import org.springframework.stereotype.Component;

@Component
public class LabResultRoutes extends RouteBuilder {

    @Override
    public void configure() {
        from("file:/var/care/lab/inbox?move=.done&readLock=changed")
                .routeId("lab-results-file")
                .convertBodyTo(String.class)
                .to("direct:lab-results");

        from("direct:lab-results")
                .routeId("lab-results-import")
                .bean(LabResultImporter.class, "importFile");
    }
}
