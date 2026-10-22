package com.acme.care.crm.web;

import com.acme.care.crm.service.CrmImportService;
import com.acme.care.crm.service.CrmImportService.ImportReport;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@ConditionalOnProperty(prefix = "care.crm.import", name = "enabled", havingValue = "true")
@RequestMapping(path = "api/crm/import", method = RequestMethod.POST)
public class CrmImportController {

    private final CrmImportService importService;

    public CrmImportController(CrmImportService importService) {
        this.importService = importService;
    }

    @RequestMapping("contacts")
    public ImportReport importContacts(@RequestParam("file") MultipartFile file) {
        return importService.importContacts(file);
    }

    @RequestMapping(path = "leads/", consumes = "text/csv")
    public ImportReport importLeads(@RequestParam(name = "dryRun", defaultValue = "false") boolean dryRun,
                                    @RequestParam("file") MultipartFile file) {
        return importService.importLeads(file, dryRun);
    }
}
