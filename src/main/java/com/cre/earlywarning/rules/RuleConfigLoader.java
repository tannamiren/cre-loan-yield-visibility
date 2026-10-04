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

    public RuleConfig load(int version) {
        Resource resource = resourceLoader.getResource("classpath:rules/rules-v" + version + ".yaml");
        try (InputStream in = resource.getInputStream()) {
            // SnakeYAML always parses a YAML document whose root is a mapping into a
            // Map<String, Object>, so this cast is safe for well-formed rule config files.
            @SuppressWarnings("unchecked")
            Map<String, Object> root = new Yaml().load(in);
            @SuppressWarnings("unchecked")
            Map<String, Object> limits = (Map<String, Object>) root.get("limits");
            if (limits == null) {
                throw new IllegalStateException("Missing 'limits' key in rules-v" + version + ".yaml");
            }

            Object r1 = limits.get("r1LatePaymentsThreshold");
            if (r1 == null) {
                throw new IllegalStateException("Missing 'r1LatePaymentsThreshold' in rules-v" + version + ".yaml");
            }
            Object r2 = limits.get("r2LowDscrThreshold");
            if (r2 == null) {
                throw new IllegalStateException("Missing 'r2LowDscrThreshold' in rules-v" + version + ".yaml");
            }
            Object r3 = limits.get("r3MaturitySoonMonths");
            if (r3 == null) {
                throw new IllegalStateException("Missing 'r3MaturitySoonMonths' in rules-v" + version + ".yaml");
            }
            Object r4Delta = limits.get("r4DscrFallDelta");
            if (r4Delta == null) {
                throw new IllegalStateException("Missing 'r4DscrFallDelta' in rules-v" + version + ".yaml");
            }
            Object r4Months = limits.get("r4DscrFallMonths");
            if (r4Months == null) {
                throw new IllegalStateException("Missing 'r4DscrFallMonths' in rules-v" + version + ".yaml");
            }
            Object r5Threshold = limits.get("r5LowDebtYieldThreshold");
            if (r5Threshold == null) {
                throw new IllegalStateException("Missing 'r5LowDebtYieldThreshold' in rules-v" + version + ".yaml");
            }
            Object r5Months = limits.get("r5MaturityMonths");
            if (r5Months == null) {
                throw new IllegalStateException("Missing 'r5MaturityMonths' in rules-v" + version + ".yaml");
            }

            return new RuleConfig(
                (Integer) root.get("version"),
                (Integer) r1,
                new BigDecimal(r2.toString()),
                (Integer) r3,
                new BigDecimal(r4Delta.toString()),
                (Integer) r4Months,
                new BigDecimal(r5Threshold.toString()),
                (Integer) r5Months
            );
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load rule config version " + version, e);
        }
    }
}
