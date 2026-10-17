# Keycloak with Solace's code-sign-in and temporary-password authenticators and the staff sign-in
# theme built in. Built from the repository root after `./gradlew :keycloak:otp-authenticator:jar`
# and `pnpm install` (which generates the theme's tokens, fonts and logo):
#   docker build -f infra/keycloak/Dockerfile -t solace/keycloak .
#
# Realms are not baked in: the JSON under infra/keycloak/realms carries development secrets and is
# for local use only. Deployed realms are managed separately (infra/keycloak/README.md).
ARG KEYCLOAK_IMAGE=quay.io/keycloak/keycloak:26.3.1

FROM ${KEYCLOAK_IMAGE} AS builder
ENV KC_DB=postgres \
    KC_HEALTH_ENABLED=true \
    KC_METRICS_ENABLED=true
COPY backend/keycloak/otp-authenticator/build/libs/solace-otp-authenticator.jar /opt/keycloak/providers/
COPY infra/keycloak/themes/solace-staff/ /opt/keycloak/themes/solace-staff/
RUN /opt/keycloak/bin/kc.sh build

FROM ${KEYCLOAK_IMAGE}
COPY --from=builder /opt/keycloak/ /opt/keycloak/
ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
CMD ["start", "--optimized"]
