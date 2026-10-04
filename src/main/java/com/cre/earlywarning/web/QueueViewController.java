package com.cre.earlywarning.web;

import com.cre.earlywarning.alerts.AlertService;
import com.cre.earlywarning.api.AlertDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;

@Controller
public class QueueViewController {

    private final AlertService alertService;
    private final ObjectMapper objectMapper;

    public QueueViewController(AlertService alertService, ObjectMapper objectMapper) {
        this.alertService = alertService;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/")
    public String queue(Model model) {
        var alerts = alertService.openQueue().stream().map(a -> AlertDto.from(a, objectMapper)).toList();
        model.addAttribute("alerts", alerts);
        return "queue";
    }

    @PostMapping("/ui/alerts/{id}/acknowledge")
    public String acknowledge(@PathVariable Long id) {
        alertService.acknowledge(id);
        return "redirect:/";
    }
}
