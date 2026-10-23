package com.acme.care.legacy.portlet;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.portlet.bind.annotation.ActionMapping;
import org.springframework.web.portlet.bind.annotation.RenderMapping;
import org.springframework.web.portlet.bind.annotation.ResourceMapping;

import javax.portlet.ActionRequest;
import javax.portlet.ActionResponse;
import javax.portlet.RenderRequest;
import javax.portlet.ResourceRequest;
import javax.portlet.ResourceResponse;

@Controller
@RequestMapping("VIEW")
public class ResidentAccountPortlet extends BasePortalPortlet {

    private String lastSearch;

    @RenderMapping
    public String showAccount(RenderRequest request) {
        request.setAttribute("lastSearch", lastSearch);
        return "account";
    }

    @ActionMapping("save")
    public void saveAccount(ActionRequest request, ActionResponse response) {
        lastSearch = request.getParameter("query");
    }

    @ResourceMapping("export")
    public void exportAccount(ResourceRequest request, ResourceResponse response) {
        response.setContentType("text/csv");
    }
}
