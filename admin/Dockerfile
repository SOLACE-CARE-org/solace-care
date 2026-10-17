# The admin portal: the Angular build served by unprivileged nginx. CI runs `pnpm build` first, then:
#   docker build -t solace/admin admin
# The same image runs in every environment; the variables below fill /config.json and the
# Content-Security-Policy when the container starts.
FROM nginxinc/nginx-unprivileged:1.29.1-alpine

ENV KEYCLOAK_URL=http://localhost:8180 \
    KEYCLOAK_REALM=solace-staff \
    KEYCLOAK_CLIENT_ID=solace-admin \
    API_URL=http://localhost:8080/api \
    IDLE_SIGN_OUT_MINUTES=15 \
    FLAG_TRACKING_MODULES=false \
    FLAG_HEALTH_CHECK=false \
    FLAG_AI_CONTROLS=false \
    FLAG_COPILOT=false

COPY nginx/default.conf.template /etc/nginx/templates/default.conf.template
COPY nginx/security-headers.conf.template /etc/nginx/templates/security-headers.conf.template
COPY dist/admin/browser/ /usr/share/nginx/html/

EXPOSE 8080
