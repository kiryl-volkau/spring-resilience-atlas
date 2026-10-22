package com.acme.care.billing.schedule;

import java.time.YearMonth;

import com.acme.care.billing.service.DunningService;
import com.acme.care.billing.service.InvoiceRunService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class InvoiceRunScheduler {

    private final InvoiceRunService invoiceRunService;
    private final DunningService dunningService;

    public InvoiceRunScheduler(InvoiceRunService invoiceRunService, DunningService dunningService) {
        this.invoiceRunService = invoiceRunService;
        this.dunningService = dunningService;
    }

    @Scheduled(cron = "0 0 2 1 * *", zone = "Europe/London")
    public void generateMonthlyInvoices() {
        invoiceRunService.runFor(YearMonth.now().minusMonths(1));
    }

    @Scheduled(cron = "${care.billing.dunning-cron:0 30 6 * * MON-FRI}")
    public void sendDunningReminders() {
        dunningService.remindOverdueAccounts();
    }
}
