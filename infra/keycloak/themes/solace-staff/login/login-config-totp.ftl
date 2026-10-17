<#-- First sign-in, step 2: add an authenticator app (FirstSignIn.dc.html, card 2). -->
<#import "template.ftl" as layout>
<#assign hasError = messagesPerField.existsError('totp')>
<@layout.registrationLayout displayMessage=!hasError displayInfo=true wide=true errorsOnly=true; section>
  <#if section = "header">
    ${msg("solaceSetUpTitle")}
  <#elseif section = "form">
    <form id="kc-totp-settings-form" class="sl-form" action="${url.loginAction}" method="post">
      <section class="sl-card" aria-labelledby="sl-step-authenticator">
        <h2 id="sl-step-authenticator" class="sl-step">${msg("solaceStepAuthenticator")}</h2>
        <p class="sl-footnote">${msg("solaceScanCode")}</p>
        <div class="sl-qr-row">
          <img class="sl-qr" src="data:image/png;base64, ${totp.totpSecretQrCode}" alt="${msg("solaceQrAlt")}" width="130" height="130">
          <div class="sl-key-block">
            <span>${msg("solaceCantScan")}</span>
            <code class="sl-key">${totp.totpSecretEncoded}</code>
          </div>
        </div>
        <label class="sl-field" for="totp">${msg("solaceCodeFromApp")}
          <input id="totp" name="totp" class="sl-code" type="text" inputmode="numeric"
                 autocomplete="one-time-code" autofocus placeholder="000 000"
                 <#if hasError>aria-invalid="true" aria-describedby="sl-totp-error"</#if>>
        </label>
        <#if hasError>
          <p id="sl-totp-error" class="sl-field-error" role="alert">${kcSanitize(messagesPerField.get('totp'))?no_esc}</p>
        </#if>
        <input type="hidden" id="totpSecret" name="totpSecret" value="${totp.totpSecret}">
        <#if mode??><input type="hidden" id="mode" name="mode" value="${mode}"></#if>
      </section>
      <button class="sl-button" type="submit" id="saveTOTPBtn">${msg("solaceFinish")}</button>
    </form>
  <#elseif section = "info">
    <p class="sl-footnote">${msg("solaceTemporaryExpires")}</p>
  </#if>
</@layout.registrationLayout>
