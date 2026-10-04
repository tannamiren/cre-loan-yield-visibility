package com.cre.earlywarning.api;

import com.cre.earlywarning.alerts.AlertService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/alerts")
public class AlertController {

    private final AlertService alertService;
    private final ObjectMapper objectMapper;

    public AlertController(AlertService alertService, ObjectMapper objectMapper) {
        this.alertService = alertService;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    public List<AlertDto> list() {
        return alertService.openQueue().stream().map(a -> AlertDto.from(a, objectMapper)).toList();
    }

    @PostMapping("/{id}/acknowledge")
    public ResponseEntity<Void> acknowledge(@PathVariable Long id) {
        alertService.acknowledge(id);
        return ResponseEntity.ok().build();
    }
}
