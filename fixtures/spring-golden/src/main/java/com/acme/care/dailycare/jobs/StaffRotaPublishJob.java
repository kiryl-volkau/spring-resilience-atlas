package com.acme.care.dailycare.jobs;

import com.acme.care.dailycare.rota.StaffRotaService;
import com.acme.care.shared.jobs.AbstractCareHomeJob;
import org.quartz.JobExecutionContext;

public class StaffRotaPublishJob extends AbstractCareHomeJob {

    private StaffRotaService rotas;

    public void setRotas(StaffRotaService rotas) {
        this.rotas = rotas;
    }

    @Override
    protected void runForHome(String homeCode, JobExecutionContext context) {
        rotas.publishNextWeek(homeCode);
    }
}
