<#import "template.ftl" as layout>
<@layout.registrationLayout displayInfo=true; section>
    <#if section = "header">
        ${title!msg("mfaEnrollmentTitle")}
    <#elseif section = "form">
        <p class="instruction">${description!""}</p>
        <#if message??>
            <div class="${properties.kcAlertClass!} ${properties.kcAlertErrorClass!}">
                <span class="${properties.kcFeedbackErrorIcon!}"></span>
                <span class="${properties.kcAlertTitleClass!}">${message}</span>
            </div>
        </#if>
        <form action="${url.loginAction}" class="${properties.kcFormClass!}" id="kc-mfa-enrollment-form" method="post">
            <div class="${properties.kcFormGroupClass!}">
                <div class="${properties.kcInputWrapperClass!}">
                    <#list mfaMethods as method>
                        <div class="kc-mfa-method <#if method.configured>kc-configured</#if> <#if !method.available>kc-unavailable</#if>">
                            <label>
                                <input type="checkbox"
                                       name="method"
                                       value="${method.id}"
                                       <#if method.configured || !method.available>disabled</#if>>
                                <span class="kc-method-label">${method.label}</span>
                            </label>
                            <#if method.configured>
                                <span class="kc-badge">${msg("configured","Configured")}</span>
                            <#elseif !method.available>
                                <span class="kc-badge">${msg("notAvailable","Unavailable")}</span>
                            </#if>
                            <div class="kc-method-description">${method.description}</div>
                        </div>
                    </#list>
                    <#if !hasSelectable?? || !hasSelectable>
                        <div class="kc-feedback-text">${msg("noAdditionalMfaAvailable","No additional methods available.")}</div>
                    </#if>
                </div>
            </div>

            <#if allowOptOut?? && allowOptOut>
                <div class="${properties.kcFormGroupClass!}">
                    <label class="checkbox">
                        <input type="checkbox" name="optOut">
                        ${msg("dontAskAgain","Don't ask me again")}
                    </label>
                </div>
            </#if>

            <div class="${properties.kcFormGroupClass!}">
                <div id="kc-form-buttons" class="${properties.kcFormButtonsClass!}">
                    <input class="${properties.kcButtonClass!} ${properties.kcButtonPrimaryClass!} ${properties.kcButtonBlockClass!} ${properties.kcButtonLargeClass!}"
                           type="submit"
                           value="${msg("doSubmit")}"/>
                </div>
            </div>
        </form>
    </#if>
</@layout.registrationLayout>
