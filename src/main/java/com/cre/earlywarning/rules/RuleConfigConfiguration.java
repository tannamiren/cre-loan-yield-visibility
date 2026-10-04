package com.cre.earlywarning.rules;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;

@Configuration
public class RuleConfigConfiguration {

    @Bean
    public RuleConfig ruleConfig(ResourceLoader resourceLoader,
                                  @Value("${app.rules.active-version}") int activeVersion) {
        return new RuleConfigLoader(resourceLoader, activeVersion).loadActive();
    }
}
