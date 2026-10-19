package care.solace.core.system;

import care.solace.security.GrpcMethodScopes;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The scope each gRPC method of Core requires (infra/keycloak/realms/solace-staff-realm.json). A
 * method missing here is refused, even for a valid token.
 */
@Configuration(proxyBeanMethods = false)
class GrpcConfiguration {

  @Bean
  GrpcMethodScopes grpcMethodScopes() {
    return new GrpcMethodScopes(
        Map.of(
            "solace.core.v1.BookingAccessService/CheckLiveBooking", "core:booking-access.read",
            "solace.core.v1.TaxonomyService/ListSpecialties", "core:taxonomy.read",
            "solace.core.v1.ProfileService/GetAge", "core:profile-age.read",
            "solace.core.v1.BriefSourceService/GetBriefSources", "core:brief-sources.read"));
  }
}
