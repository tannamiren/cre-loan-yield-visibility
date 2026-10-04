package com.cre.earlywarning.rules;

import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.Map;

public class RuleConfigLoader {

    private final ResourceLoader resourceLoader;
    private final int activeVersion;

    public RuleConfigLoader(ResourceLoader resourceLoader, int activeVersion) {
        this.resourceLoader = resourceLoader;
        this.activeVersion = activeVersion;
    }

    public RuleConfig loadActive() {
        return load(activeVersion);
    }

    @SuppressWarnings("unchecked")
    public RuleConfig load(int version) {
        Resource resource = resourceLoader.getResource("classpath:rules/rules-v" + version + ".yaml");
        try (InputStream in = resource.getInputStream()) {
            Map<String, Object> root = new Yaml().load(in);
            Map<String, Object> limits = (Map<String, Object>) root.get("limits");
            return new RuleConfig(
                (Integer) root.get("version"),
                (Integer) limits.get("r1LatePaymentsThreshold"),
                new BigDecimal(limits.get("r2LowDscrThreshold").toString()),
                (Integer) limits.get("r3MaturitySoonMonths"),
                new BigDecimal(limits.get("r4DscrFallDelta").toString()),
                (Integer) limits.get("r4DscrFallMonths"),
                new BigDecimal(limits.get("r5LowDebtYieldThreshold").toString()),
                (Integer) limits.get("r5MaturityMonths")
            );
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load rule config version " + version, e);
        }
    }
}
