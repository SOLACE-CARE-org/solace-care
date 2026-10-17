<#-- Staff sign in: email and password (Main.dc.html). The authenticator code is the next page. -->
<#import "template.ftl" as layout>
<#assign hasError = messagesPerField.existsError('username','password')>
<@layout.registrationLayout displayMessage=!hasError displayInfo=true; section>
  <#if section = "header">
    ${msg("solaceSignInTitle")}
  <#elseif section = "form">
    <p class="sl-lead">${msg("solaceSignInLead")}</p>
    <form id="kc-form-login" class="sl-form" action="${url.loginAction}" method="post" novalidate>
      <label class="sl-field" for="username">${msg("solaceWorkEmail")}
        <input id="username" name="username" type="email" value="${(login.username!'')}"
               autocomplete="username" autofocus
               <#if hasError>aria-invalid="true" aria-describedby="sl-login-error"</#if>>
      </label>
      <label class="sl-field" for="password">${msg("solacePassword")}
        <input id="password" name="password" type="password" autocomplete="current-password"
               <#if hasError>aria-invalid="true" aria-describedby="sl-login-error"</#if>>
      </label>
      <#if hasError>
        <p id="sl-login-error" class="sl-field-error" role="alert">${kcSanitize(messagesPerField.getFirstError('username','password'))?no_esc}</p>
      </#if>
      <input type="hidden" id="id-hidden-input" name="credentialId" <#if auth.selectedCredential?has_content>value="${auth.selectedCredential}"</#if>>
      <button class="sl-button" type="submit" name="login" id="kc-login">${msg("solaceSignIn")}</button>
      <#if realm.resetPasswordAllowed>
        <a class="sl-link" href="${url.loginResetCredentialsUrl}">${msg("solaceForgotPassword")}</a>
      </#if>
    </form>
  <#elseif section = "info">
    <div class="sl-card sl-small">${msg("solaceFirstTime")}</div>
    <p class="sl-footnote">${msg("solaceSessionsEnd", properties.solaceIdleMinutes)}</p>
  </#if>
</@layout.registrationLayout>
