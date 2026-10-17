<#-- First sign-in, step 1: a new password (FirstSignIn.dc.html, card 1). Step 2 is login-config-totp.
     The design shows both on one page; Keycloak runs them as two required actions, in this order. -->
<#import "template.ftl" as layout>
<#assign hasError = messagesPerField.existsError('password','password-confirm')>
<#assign check><svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M5 12l5 5L20 7"/></svg></#assign>
<@layout.registrationLayout displayMessage=!hasError displayInfo=true wide=true errorsOnly=true; section>
  <#if section = "header">
    ${msg("solaceSetUpTitle")}
  <#elseif section = "subtitle">
    <p class="sl-subtitle">${msg("solaceTemporaryWorked")}</p>
  <#elseif section = "form">
    <form id="kc-passwd-update-form" class="sl-form" action="${url.loginAction}" method="post">
      <section class="sl-card" aria-labelledby="sl-step-password">
        <h2 id="sl-step-password" class="sl-step">${msg("solaceStepPassword")}</h2>
        <label class="sl-field" for="password-new">${msg("solaceNewPassword")}
          <input id="password-new" name="password-new" type="password" autocomplete="new-password" autofocus
                 <#if hasError>aria-invalid="true" aria-describedby="sl-password-error"</#if>>
        </label>
        <label class="sl-field" for="password-confirm">${msg("solaceConfirmPassword")}
          <input id="password-confirm" name="password-confirm" type="password" autocomplete="new-password"
                 <#if hasError>aria-invalid="true" aria-describedby="sl-password-error"</#if>>
        </label>
        <#if hasError>
          <p id="sl-password-error" class="sl-field-error" role="alert">${kcSanitize(messagesPerField.getFirstError('password','password-confirm'))?no_esc}</p>
        </#if>
        <#-- Only checks that are in place are listed; the leaked-password check is not yet. -->
        <ul class="sl-hints">
          <li>${check?no_esc}${msg("solaceHintLength")}</li>
          <li>${check?no_esc}${msg("solaceHintHistory")}</li>
        </ul>
      </section>
      <button class="sl-button" type="submit">${msg("solaceContinue")}</button>
    </form>
  <#elseif section = "info">
    <p class="sl-footnote">${msg("solaceTemporaryExpires")}</p>
  </#if>
</@layout.registrationLayout>
