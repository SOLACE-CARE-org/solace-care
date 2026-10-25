package care.solace.core.admin;

import care.solace.contracts.core.v1.ConfigServiceGrpc;
import care.solace.contracts.core.v1.FeatureFlag;
import care.solace.contracts.core.v1.GetPlatformConfigRequest;
import care.solace.contracts.core.v1.GetPlatformConfigResponse;
import care.solace.contracts.core.v1.PlatformSetting;
import io.grpc.stub.StreamObserver;
import org.springframework.stereotype.Service;

/**
 * The platform settings staff set, and every flag, for Tracking and AI (D61). They load it at
 * start, again on core.config.changed and every few minutes, and keep their own defaults for keys
 * staff never set.
 */
@Service
class ConfigEndpoint extends ConfigServiceGrpc.ConfigServiceImplBase {

  private final Settings settings;
  private final SettingCatalogue catalogue;

  ConfigEndpoint(Settings settings, SettingCatalogue catalogue) {
    this.settings = settings;
    this.catalogue = catalogue;
  }

  @Override
  public void getPlatformConfig(
      GetPlatformConfigRequest request, StreamObserver<GetPlatformConfigResponse> out) {
    var response = GetPlatformConfigResponse.newBuilder();
    settings
        .setValues()
        .forEach(
            (key, current) ->
                catalogue
                    .setting(key)
                    .filter(SettingCatalogue.Definition::editable)
                    .ifPresent(
                        d ->
                            response.addSettings(
                                PlatformSetting.newBuilder()
                                    .setKey(key)
                                    .setValue(current.value())
                                    .setUnit(d.type().name())
                                    .setVersion(current.version()))));
    for (var flag : catalogue.flags()) {
      response.addFlags(
          FeatureFlag.newBuilder().setKey(flag.key()).setEnabled(settings.enabled(flag.key())));
    }
    out.onNext(response.build());
    out.onCompleted();
  }
}
