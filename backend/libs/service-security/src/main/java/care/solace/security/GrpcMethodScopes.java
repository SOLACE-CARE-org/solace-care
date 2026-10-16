package care.solace.security;

import java.util.Map;

/**
 * The scope each gRPC method of a service requires, by full method name ({@code
 * solace.core.v1.BookingAccessService/CheckLiveBooking}). Every service with a gRPC server declares
 * one bean of this type; methods that are not listed are refused.
 *
 * @param required full method name to scope, e.g. {@code core:booking-access.read}
 */
public record GrpcMethodScopes(Map<String, String> required) {

  public GrpcMethodScopes {
    required = Map.copyOf(required);
  }
}
