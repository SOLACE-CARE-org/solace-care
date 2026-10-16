package care.solace.locale;

import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.LocaleResolver;

/**
 * Registers {@link ViewerLocaleResolver} for servlet services. {@link LocaleDefaults} makes message
 * lookups fall back to the English file, never to the server's own language.
 */
@AutoConfiguration(before = WebMvcAutoConfiguration.class)
public class LocaleAutoConfiguration {

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
  @ConditionalOnClass(LocaleResolver.class)
  static class Servlet {

    @Bean(name = DispatcherServlet.LOCALE_RESOLVER_BEAN_NAME)
    @ConditionalOnMissingBean(name = DispatcherServlet.LOCALE_RESOLVER_BEAN_NAME)
    LocaleResolver localeResolver() {
      return new ViewerLocaleResolver(storedLocale());
    }

    private static Supplier<Optional<String>> storedLocale() {
      if (!isPresent("org.springframework.security.oauth2.jwt.Jwt")) {
        return Optional::empty;
      }
      return JwtClaim::locale;
    }

    private static boolean isPresent(String className) {
      try {
        Class.forName(className, false, LocaleAutoConfiguration.class.getClassLoader());
        return true;
      } catch (ClassNotFoundException e) {
        return false;
      }
    }
  }

  /** Reads the {@code locale} claim Keycloak puts in access tokens. */
  static final class JwtClaim {
    private JwtClaim() {}

    static Optional<String> locale() {
      Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
      if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
        return Optional.ofNullable(jwt.getClaimAsString("locale"));
      }
      return Optional.empty();
    }
  }
}
