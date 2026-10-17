package care.solace.core.specialists;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Settings of the specialists module. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SpecialistSettings.class)
class SpecialistsConfiguration {}
