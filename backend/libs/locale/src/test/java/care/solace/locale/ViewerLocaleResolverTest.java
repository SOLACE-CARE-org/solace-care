package care.solace.locale;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.LocaleResolver;

class ViewerLocaleResolverTest {

  @AfterEach
  void clearSecurity() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void storedLanguageWinsOverTheBrowser() {
    ViewerLocaleResolver resolver = new ViewerLocaleResolver(() -> Optional.of("fr"));

    assertThat(resolver.resolveLocale(request("en-GB,en;q=0.9"))).isEqualTo(Locale.FRENCH);
  }

  @ParameterizedTest
  @CsvSource({
    "'fr-CM,fr;q=0.9,en;q=0.8', fr",
    "'de-DE,fr;q=0.5', fr",
    "'en-US', en",
    "'de,es', en",
    "'', en",
    "'not a header;;', en",
  })
  void otherwiseUsesTheFirstSupportedBrowserLanguage(String header, String expected) {
    ViewerLocaleResolver resolver = new ViewerLocaleResolver(Optional::empty);

    assertThat(resolver.resolveLocale(request(header)).getLanguage()).isEqualTo(expected);
  }

  @Test
  void unsupportedStoredLanguageFallsThroughToTheBrowser() {
    ViewerLocaleResolver resolver = new ViewerLocaleResolver(() -> Optional.of("de"));

    assertThat(resolver.resolveLocale(request("fr"))).isEqualTo(Locale.FRENCH);
  }

  @Test
  void autoConfigurationReadsTheLocaleClaimFromKeycloakTokens() {
    new WebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(LocaleAutoConfiguration.class))
        .run(
            context -> {
              LocaleResolver resolver =
                  context.getBean(
                      DispatcherServlet.LOCALE_RESOLVER_BEAN_NAME, LocaleResolver.class);
              Jwt jwt =
                  Jwt.withTokenValue("t")
                      .header("alg", "none")
                      .claim("sub", "user-1")
                      .claim("locale", "fr")
                      .issuedAt(Instant.now())
                      .build();
              SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));

              assertThat(resolver.resolveLocale(request("en"))).isEqualTo(Locale.FRENCH);
            });
  }

  @Test
  void serviceCanSupplyItsOwnResolver() {
    new WebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(LocaleAutoConfiguration.class))
        .withBean(
            DispatcherServlet.LOCALE_RESOLVER_BEAN_NAME,
            LocaleResolver.class,
            () -> new ViewerLocaleResolver(() -> Optional.of("en")))
        .run(context -> assertThat(context).hasSingleBean(LocaleResolver.class));
  }

  private static MockHttpServletRequest request(String acceptLanguage) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    if (!acceptLanguage.isEmpty()) {
      request.addHeader("Accept-Language", acceptLanguage);
    }
    return request;
  }
}
