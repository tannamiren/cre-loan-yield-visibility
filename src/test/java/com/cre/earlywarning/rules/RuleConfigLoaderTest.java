package com.cre.earlywarning.rules;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuleConfigLoaderTest {

    @Test
    void loadsVersion1LimitsFromClasspathYaml() {
        RuleConfigLoader loader = new RuleConfigLoader(new DefaultResourceLoader(), 1);

        RuleConfig config = loader.loadActive();

        assertThat(config.version()).isEqualTo(1);
        assertThat(config.r1LatePaymentsThreshold()).isEqualTo(2);
        assertThat(config.r2LowDscrThreshold()).isEqualByComparingTo("1.10");
        assertThat(config.r3MaturitySoonMonths()).isEqualTo(3);
        assertThat(config.r4DscrFallDelta()).isEqualByComparingTo("0.15");
        assertThat(config.r4DscrFallMonths()).isEqualTo(6);
        assertThat(config.r5LowDebtYieldThreshold()).isEqualByComparingTo("0.08");
        assertThat(config.r5MaturityMonths()).isEqualTo(18);
    }

    @Test
    void throwsDescriptiveErrorWhenLimitsKeyIsMissing() {
        RuleConfigLoader loader = new RuleConfigLoader(new DefaultResourceLoader(), 999);

        assertThatThrownBy(loader::loadActive)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Missing 'limits'")
            .hasMessageContaining("rules-v999.yaml");
    }

    @Test
    void throwsDescriptiveErrorWhenASpecificLimitKeyIsMissing() {
        RuleConfigLoader loader = new RuleConfigLoader(new DefaultResourceLoader(), 998);

        assertThatThrownBy(loader::loadActive)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Missing 'r5MaturityMonths'")
            .hasMessageContaining("rules-v998.yaml");
    }

    @Test
    void throwsDescriptiveErrorWhenConfigFileIsMissing() {
        RuleConfigLoader loader = new RuleConfigLoader(new DefaultResourceLoader(), 12345);

        assertThatThrownBy(loader::loadActive)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Cannot load rule config version 12345");
    }
}
