<#import "template.ftl" as layout>
<@layout.registrationLayout displayInfo=true; section>
    <#if section = "header">
        ${msg("mfaEnrollmentTitle")}
    <#elseif section = "form">
        <form action="${url.loginAction}" class="${properties.kcFormClass!}" id="kc-mfa-enrollment-form" method="post">
            <div class="${properties.kcFormGroupClass!}">
                <div class="${properties.kcLabelWrapperClass!}">
                    <label for="mfa-method" class="${properties.kcLabelClass!}">${msg("selectMfaMethod")}</label>
                </div>
                <div class="${properties.kcInputWrapperClass!}">
                    <#list mfaMethods as method>
                        <div class="checkbox">
                            <label>
                                <input type="checkbox" name="selectedMethods" value="${method.id}">
                                ${method.label}
                            </label>
                        </div>
                    </#list>
                </div>
            </div>

            <div class="${properties.kcFormGroupClass!}">
                <div id="kc-form-buttons" class="${properties.kcFormButtonsClass!}">
                    <input class="${properties.kcButtonClass!} ${properties.kcButtonPrimaryClass!} ${properties.kcButtonBlockClass!} ${properties.kcButtonLargeClass!}" type="submit" value="${msg("doSubmit")}"/>
                </div>
            </div>
        </form>
    </#if>
</@layout.registrationLayout>
