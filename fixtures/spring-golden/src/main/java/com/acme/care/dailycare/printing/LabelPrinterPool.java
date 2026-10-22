package com.acme.care.dailycare.printing;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class LabelPrinterPool implements InitializingBean, DisposableBean {

    private final List<String> printerHosts;
    private final List<LabelPrinterConnection> connections = new ArrayList<>();

    public LabelPrinterPool(@Value("${care.dailycare.label-printers:}") List<String> printerHosts) {
        this.printerHosts = printerHosts;
    }

    @Override
    public void afterPropertiesSet() {
        for (String host : printerHosts) {
            connections.add(LabelPrinterConnection.open(host));
        }
    }

    @Override
    public void destroy() {
        connections.forEach(LabelPrinterConnection::close);
        connections.clear();
    }
}
