package care.solace.core.specialists;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

/**
 * Specialist onboarding settings ({@code solace.specialists.*}). Admin settings will own these
 * values (admin portal v1, Settings and flags); until then they are configuration.
 *
 * @param termsVersion the Specialist terms version a new specialist must accept
 * @param codeOfConductVersion likewise for the Code of conduct
 * @param pricing the rules SpecServices shows and Core enforces
 * @param documents where credential documents go and how large they may be
 * @param bankTransferEnabled the {@code payout-bank-transfer} flag, off until the bank details to
 *     collect are specified (D26)
 */
@ConfigurationProperties("solace.specialists")
@Validated
record SpecialistSettings(
    @DefaultValue("v1") String termsVersion,
    @DefaultValue("v1") String codeOfConductVersion,
    @NotNull @Valid Pricing pricing,
    @DefaultValue @Valid Documents documents,
    @DefaultValue("false") boolean bankTransferEnabled) {

  /**
   * SpecServices: "Allowed range: [Min] to [Max] FCFA per session" and "Solace's [n]% fee". The
   * range is decided (D27) and set in application.yml. The fee is not (decisions.md: the commission
   * rate is open), so every environment sets it, and Core refuses to start without it.
   *
   * @param minPrice lowest price per session, whole FCFA
   * @param maxPrice highest price per session, whole FCFA
   * @param feePercent Solace's fee on each completed session
   * @param durations session lengths in minutes a specialist can choose (SpecServices)
   */
  record Pricing(
      @NotNull @PositiveOrZero Long minPrice,
      @NotNull @PositiveOrZero Long maxPrice,
      @NotNull @DecimalMin("0") @DecimalMax("100") BigDecimal feePercent,
      @DefaultValue({"30", "50", "80"}) @NotEmpty List<Integer> durations) {}

  /**
   * @param bucket the private object storage bucket for credentials
   * @param maxFileSize the largest file accepted. Not in the specs: a setting
   */
  record Documents(
      @DefaultValue("solace-credentials") String bucket,
      @DefaultValue("10MB") DataSize maxFileSize) {}
}
