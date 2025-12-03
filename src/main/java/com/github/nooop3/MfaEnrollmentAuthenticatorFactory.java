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
                List<ProviderConfigProperty> props = new ArrayList<>();

                // Triggering conditions
                props.add(string("min_required_mfa_methods", "Min Required MFA Methods", "1",
                                "Minimum number of distinct MFA methods a user must have configured overall."));
                props.add(string("min_required_from_list", "Min Required From List", "1",
                                "Minimum number of methods from the enabled list that the user must have."));
                props.add(string("max_allowed_mfa_methods", "Max Allowed MFA Methods", null,
                                "Skip prompting if the user already has this many methods configured."));
                props.add(bool("enforce_on_first_login_only", "Enforce On First Login Only", false,
                                "When true, only prompt on the user's first successful login."));
                props.add(list("enforce_for_idp_users", "Enforce For IdP Users", "always",
                                List.of("always", "never", "only"),
                                "Control behavior for brokered IdP logins."));

                // Supported methods
                props.add(multivalued("enabled_mfa_types", "Enabled MFA Types",
                                "otp,webauthn,recovery-authn-code",
                                "List of MFA types the user can choose from (e.g. otp, webauthn, recovery-authn-code)."));
                props.add(bool("visible_only_if_supported", "Hide Unsupported Methods", true,
                                "Show methods only when the backing required action/authenticator is available."));
                props.add(bool("hide_already_configured_methods", "Hide Configured Methods", false,
                                "Remove already-configured methods from the selection list."));

                // Selection rules
                props.add(list("selection_mode", "Selection Mode", "at_least_one",
                                List.of("at_least_one", "exactly_one", "all_unconfigured", "up_to_max"),
                                "Rule for how many methods the user must select."));
                props.add(string("max_new_methods_per_login", "Max New Methods Per Login", null,
                                "Cap the number of new methods that can be started in a single login (used with up_to_max)."));
                props.add(bool("fail_if_selection_insufficient", "Fail If Selection Insufficient", true,
                                "Fail the login when the user selection does not meet requirements."));
                props.add(bool("allow_no_selection_if_already_sufficient", "Allow No Selection If Already Sufficient",
                                true,
                                "Let users proceed without selecting new methods when they already meet minimums."));

                // Post-auth behavior
                props.add(bool("offer_configure_additional_methods", "Offer Additional Methods", true,
                                "Invite the user to configure more methods after meeting minimums."));
                props.add(list("post_auth_prompt_mode", "Post-Auth Prompt Mode", "same_login",
                                List.of("same_login", "next_login_required_action", "none"),
                                "When to prompt for additional configuration after minimum requirements are met."));

                // Opt-out controls
                props.add(bool("allow_user_opt_out", "Allow User Opt-Out", true,
                                "Show a 'don't ask again' option to the user."));
                props.add(bool("opt_out_respected_when_not_sufficient", "Respect Opt-Out When Not Sufficient", false,
                                "If true, opt-out is honored even if the user does not meet minimum requirements."));
                props.add(string("opt_out_attribute_name", "Opt-Out Attribute Name", "mfaEnrollment.skipFuturePrompts",
                                "User attribute used to store the opt-out flag."));

                // Rollout
                props.add(string("rollout_percentage", "Rollout Percentage", "100",
                                "Percent of users prompted when other conditions are met."));
                props.add(list("rollout_strategy", "Rollout Strategy", "hash_user_id",
                                List.of("hash_user_id", "random"),
                                "Stable hash by user or random per login."));
                props.add(bool("bypass_rollout_if_not_sufficient", "Bypass Rollout If Not Sufficient", true,
                                "Always prompt users below the minimum regardless of rollout percentage."));

                // Targeting and reminders
                props.add(multivalued("only_for_roles", "Only For Roles", null,
                                "Limit prompting to users with any of these realm roles."));
                props.add(multivalued("exclude_roles", "Exclude Roles", null,
                                "Skip prompting for users with any of these realm roles."));
                props.add(multivalued("only_for_clients", "Only For Clients", null,
                                "Limit prompting to these client IDs/aliases."));
                props.add(multivalued("exclude_clients", "Exclude Clients", null,
                                "Skip prompting for these client IDs/aliases."));
                props.add(string("remind_every_days", "Remind Every N Days", null,
                                "Minimum days between prompts to the same user."));
                props.add(multivalued("skip_if_attribute_equals", "Skip If Attribute Equals", null,
                                "Key=value pairs; if any user attribute matches, skip prompting."));

                return props;
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

        private ProviderConfigProperty string(String name, String label, String defaultValue, String helpText) {
                ProviderConfigProperty prop = new ProviderConfigProperty();
                prop.setName(name);
                prop.setLabel(label);
                prop.setType(ProviderConfigProperty.STRING_TYPE);
                prop.setDefaultValue(defaultValue);
                prop.setHelpText(helpText);
                return prop;
        }

        private ProviderConfigProperty bool(String name, String label, boolean defaultValue, String helpText) {
                ProviderConfigProperty prop = new ProviderConfigProperty();
                prop.setName(name);
                prop.setLabel(label);
                prop.setType(ProviderConfigProperty.BOOLEAN_TYPE);
                prop.setDefaultValue(defaultValue);
                prop.setHelpText(helpText);
                return prop;
        }

        private ProviderConfigProperty list(String name, String label, String defaultValue, List<String> options,
                        String helpText) {
                ProviderConfigProperty prop = new ProviderConfigProperty();
                prop.setName(name);
                prop.setLabel(label);
                prop.setType(ProviderConfigProperty.LIST_TYPE);
                prop.setDefaultValue(defaultValue);
                prop.setHelpText(helpText);
                prop.setOptions(options);
                return prop;
        }

        private ProviderConfigProperty multivalued(String name, String label, String defaultValue, String helpText) {
                ProviderConfigProperty prop = new ProviderConfigProperty();
                prop.setName(name);
                prop.setLabel(label);
                prop.setType(ProviderConfigProperty.MULTIVALUED_STRING_TYPE);
                prop.setDefaultValue(defaultValue);
                prop.setHelpText(helpText);
                return prop;
        }
}
