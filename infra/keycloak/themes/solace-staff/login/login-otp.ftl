<#-- Second step of staff sign in: the code from the authenticator app. -->
<#import "template.ftl" as layout>
<#assign hasError = messagesPerField.existsError('totp')>
<@layout.registrationLayout displayMessage=!hasError displayInfo=true; section>
  <#if section = "header">
    ${msg("solaceSignInTitle")}
  <#elseif section = "form">
    <form id="kc-otp-login-form" class="sl-form" action="${url.loginAction}" method="post">
      <#if otpLogin.userOtpCredentials?size gt 1>
        <input type="hidden" name="selectedCredentialId" value="${otpLogin.selectedCredentialId}">
      </#if>
      <label class="sl-field" for="otp">${msg("solaceCodeFromApp")}
        <input id="otp" name="otp" class="sl-code" type="text" inputmode="numeric"
               autocomplete="one-time-code" autofocus placeholder="000 000"
               <#if hasError>aria-invalid="true" aria-describedby="sl-otp-error"</#if>>
      </label>
      <#if hasError>
        <p id="sl-otp-error" class="sl-field-error" role="alert">${kcSanitize(messagesPerField.get('totp'))?no_esc}</p>
      </#if>
      <button class="sl-button" type="submit" name="login" id="kc-login">${msg("solaceSignIn")}</button>
    </form>
  <#elseif section = "info">
    <p class="sl-footnote">${msg("solaceSessionsEnd", properties.solaceIdleMinutes)}</p>
  </#if>
</@layout.registrationLayout>
