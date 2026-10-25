package care.solace.core.specialists;

import care.solace.core.admin.PlatformSettings;
import org.springframework.stereotype.Component;

/** Prices and the fee as staff last set them on Settings and flags, else as configured (D42). */
@Component
class LivePricing {

  private final SpecialistSettings settings;
  private final PlatformSettings platform;

  LivePricing(SpecialistSettings settings, PlatformSettings platform) {
    this.settings = settings;
    this.platform = platform;
  }

  SpecialistSettings.Pricing current() {
    var configured = settings.pricing();
    return new SpecialistSettings.Pricing(
        platform.number("solace.specialists.pricing.min-price", configured.minPrice()),
        platform.number("solace.specialists.pricing.max-price", configured.maxPrice()),
        platform.decimal("solace.specialists.pricing.fee-percent", configured.feePercent()),
        platform
            .numbers(
                "solace.specialists.pricing.durations",
                configured.durations().stream().map(Integer::longValue).toList())
            .stream()
            .map(Long::intValue)
            .toList());
  }
}
