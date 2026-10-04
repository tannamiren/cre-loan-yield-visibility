package com.cre.earlywarning.rules;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

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
}
