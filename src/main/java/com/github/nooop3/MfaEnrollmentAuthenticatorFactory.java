package com.github.nooop3;

import org.keycloak.Config;
import org.keycloak.authentication.Authenticator;
import org.keycloak.authentication.AuthenticatorFactory;
import org.keycloak.models.AuthenticationExecutionModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.provider.ProviderConfigProperty;

import java.util.ArrayList;
import java.util.List;

public class MfaEnrollmentAuthenticatorFactory implements AuthenticatorFactory {

    public static final String PROVIDER_ID = "mfa-enrollment-orchestrator";
    private static final MfaEnrollmentAuthenticator SINGLETON = new MfaEnrollmentAuthenticator();

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public String getDisplayType() {
        return "MFA Enrollment Orchestrator";
    }

    @Override
    public String getReferenceCategory() {
        return "mfa-enrollment";
    }

    @Override
    public boolean isConfigurable() {
        return true;
    }

    @Override
    public AuthenticationExecutionModel.Requirement[] getRequirementChoices() {
        return new AuthenticationExecutionModel.Requirement[] {
                AuthenticationExecutionModel.Requirement.REQUIRED,
                AuthenticationExecutionModel.Requirement.ALTERNATIVE,
                AuthenticationExecutionModel.Requirement.DISABLED
        };
    }

    @Override
    public boolean isUserSetupAllowed() {
        return false;
    }

    @Override
    public String getHelpText() {
        return "Orchestrates MFA enrollment based on configurable rules.";
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        List<ProviderConfigProperty> configProperties = new ArrayList<>();

        ProviderConfigProperty minRequired = new ProviderConfigProperty();
        minRequired.setName("min_required_mfa_methods");
        minRequired.setLabel("Min Required MFA Methods");
        minRequired.setType(ProviderConfigProperty.STRING_TYPE);
        minRequired.setDefaultValue("1");
        minRequired.setHelpText("Minimum number of distinct MFA methods a user must have configured overall.");
        configProperties.add(minRequired);

        ProviderConfigProperty minRequiredFromList = new ProviderConfigProperty();
        minRequiredFromList.setName("min_required_from_list");
        minRequiredFromList.setLabel("Min Required From List");
        minRequiredFromList.setType(ProviderConfigProperty.STRING_TYPE);
        minRequiredFromList.setDefaultValue("1");
        minRequiredFromList.setHelpText("Minimum number of methods from the enabled list that the user must have.");
        configProperties.add(minRequiredFromList);

        ProviderConfigProperty enabledTypes = new ProviderConfigProperty();
        enabledTypes.setName("enabled_mfa_types");
        enabledTypes.setLabel("Enabled MFA Types");
        enabledTypes.setType(ProviderConfigProperty.MULTIVALUED_LIST_TYPE);
        enabledTypes.setDefaultValue("totp");
        enabledTypes.setHelpText("List of MFA types the user can choose from (e.g. totp, webauthn).");
        configProperties.add(enabledTypes);

        ProviderConfigProperty selectionMode = new ProviderConfigProperty();
        selectionMode.setName("selection_mode");
        selectionMode.setLabel("Selection Mode");
        selectionMode.setType(ProviderConfigProperty.LIST_TYPE);
        selectionMode.setOptions(List.of("at_least_one", "exactly_one", "all_unconfigured", "up_to_max"));
        selectionMode.setDefaultValue("at_least_one");
        selectionMode.setHelpText("Rule for how many methods the user must select.");
        configProperties.add(selectionMode);

        return configProperties;
    }

    @Override
    public Authenticator create(KeycloakSession session) {
        return SINGLETON;
    }

    @Override
    public void init(Config.Scope config) {
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
    }

    @Override
    public void close() {
    }
}
