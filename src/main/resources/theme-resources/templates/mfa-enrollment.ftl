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
                        <div class="kc-mfa-method" style="margin-bottom: 15px; padding: 10px; border: 1px solid #eee; border-radius: 4px; <#if method.configured>opacity: 0.4;</#if>">
                            <div style="display: flex; align-items: center; justify-content: space-between;">
                                <label style="display: flex; align-items: center; flex-grow: 1; cursor: <#if method.configured>default<#else>pointer</#if>;">
                                    <#if !method.configured>
                                        <input type="checkbox"
                                               name="method"
                                               value="${method.id}"
                                               <#if !method.available>disabled</#if>
                                               style="margin-right: 10px;">
                                    </#if>
                                    <span class="kc-method-label" style="font-weight: bold; <#if method.configured>margin-left: 0;<#else>margin-left: 0;</#if>">${method.label}</span>
                                </label>
                                <#if method.configured>
                                    <span class="kc-badge" style="background-color: #e0e0e0; color: #555; padding: 2px 8px; border-radius: 10px; font-size: 0.8em;">${msg("configured","Configured")}</span>
                                <#elseif !method.available>
                                    <span class="kc-badge" style="background-color: #ffebee; color: #c62828; padding: 2px 8px; border-radius: 10px; font-size: 0.8em;">${msg("notAvailable","Unavailable")}</span>
                                </#if>
                            </div>
                            <div class="kc-method-description" style="margin-left: 25px; color: #666; font-size: 0.9em; margin-top: 5px;">${method.description}</div>
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
                        ${optOutLabel!msg("dontAskAgain","Don't ask me again")}
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
