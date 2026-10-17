<#-- Layout for every staff sign-in page: the brand panel and the form column (Main.dc.html). -->
<#macro registrationLayout bodyClass="" displayInfo=false displayMessage=true displayRequiredFields=false showAnotherWayIfPresent=true wide=false errorsOnly=false>
<!DOCTYPE html>
<html lang="${(locale.currentLanguageTag)!'en'}">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <meta name="robots" content="noindex, nofollow">
  <title>${msg("loginTitle")}</title>
  <link rel="icon" type="image/png" href="${url.resourcesPath}/generated/favicon.png">
  <#if properties.styles?has_content>
    <#list properties.styles?split(' ') as style>
      <link href="${url.resourcesPath}/${style}" rel="stylesheet">
    </#list>
  </#if>
</head>
<body class="${bodyClass}">
<div class="sl-page">
  <div class="sl-panel" aria-hidden="true">
    <div class="sl-disc sl-disc-top"></div>
    <div class="sl-disc sl-disc-bottom"></div>
    <div class="sl-dot"></div>
    <div class="sl-lockup">
      <img src="${url.resourcesPath}/generated/lockup.png" alt="" width="104" height="104">
      <span class="sl-pill">${msg("solaceAdminBadge")}</span>
    </div>
    <p class="sl-tagline">${msg("solaceTagline")}</p>
  </div>

  <main class="sl-main">
    <div class="sl-column<#if wide> sl-wide</#if>">
      <div class="sl-heading">
        <h1 class="sl-title"><#nested "header"></h1>
        <#if realm.internationalizationEnabled && locale.supported?size gt 1>
          <div class="sl-lang" role="group" aria-label="${msg("solaceAdminLanguage")}">
            <#list locale.supported as option>
              <#assign current = option.languageTag == locale.currentLanguageTag>
              <a href="${option.url}" lang="${option.languageTag}" hreflang="${option.languageTag}"
                 class="<#if current>sl-on</#if>" aria-current="<#if current>true<#else>false</#if>"
                 aria-label="${option.label}">${option.languageTag?upper_case}</a>
            </#list>
          </div>
        </#if>
      </div>

      <#nested "subtitle">

      <#-- errorsOnly: the first sign-in pages explain themselves, so Keycloak's "You need to..." notes
           are left out there and only real errors show. -->
      <#if displayMessage && message?has_content && (message.type != 'warning' || !isAppInitiatedAction??) && (!errorsOnly || message.type == 'error')>
        <div class="sl-notice sl-notice-${message.type}" role="alert">${kcSanitize(message.summary)?no_esc}</div>
      </#if>

      <#nested "form">

      <#if displayInfo>
        <#nested "info">
      </#if>
    </div>
  </main>
</div>
</body>
</html>
</#macro>
