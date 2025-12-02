package com.github.nooop3;

import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.Authenticator;
import org.keycloak.authentication.AuthenticationFlowError;
import org.keycloak.models.AuthenticationExecutionModel;
import org.keycloak.models.AuthenticatorConfigModel;
import org.keycloak.models.ClientModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.credential.CredentialModel;
import org.keycloak.models.credential.WebAuthnCredentialModel;
import org.keycloak.models.SubjectCredentialManager;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.ThreadLocalRandom;

public class MfaEnrollmentAuthenticator implements Authenticator {

    private static final String ATTR_LAST_PROMPT = "mfaEnrollment.lastPrompt";
    private static final String ATTR_FIRST_LOGIN_COMPLETED = "mfa.firstLoginCompleted";
    private static final int DEFAULT_MIN_REQUIRED = 1;
    private static final List<String> DEFAULT_ENABLED_TYPES = List.of("totp", "webauthn", "webauthn_passwordless",
            "recovery_codes");

    @Override
    public void authenticate(AuthenticationFlowContext context) {
        Config config = Config.fromContext(context);
        if (shouldSkipByExecution(config, context)) {
            context.success();
            return;
        }

        UserModel user = context.getUser();
        RealmModel realm = context.getRealm();
        KeycloakSession session = context.getSession();
        Set<String> configuredMethods = resolveConfiguredMethods(session, realm, user);

        List<MfaMethod> enabledMethods = resolveEnabledMethods(config, realm);
        if (config.hideAlreadyConfiguredMethods) {
            enabledMethods = enabledMethods.stream()
                    .filter(method -> !configuredMethods.contains(method.id()))
                    .toList();
        }

        boolean meetsMinimum = meetsMinimum(config, enabledMethods, configuredMethods);

        if (shouldSkipByUserAttributes(config, context, meetsMinimum)) {
            context.success();
            return;
        }

        if (shouldSkipByReminder(config, user)) {
            context.success();
            return;
        }

        if (shouldSkipByRollout(config, user, meetsMinimum)) {
            context.success();
            return;
        }

        if (config.maxAllowedMfaMethods > 0 && configuredMethods.size() >= config.maxAllowedMfaMethods) {
            context.success();
            return;
        }

        if (meetsMinimum && !config.offerConfigureAdditionalMethods) {
            markFirstLoginComplete(config, user);
            context.success();
            return;
        }

        List<MfaMethod> availableUnconfigured = enabledMethods.stream()
                .filter(method -> !configuredMethods.contains(method.id()))
                .filter(method -> !config.visibleOnlyIfSupported || method.isAvailable(realm))
                .toList();

        boolean hasUnconfigured = !availableUnconfigured.isEmpty();
        if (meetsMinimum && (!hasUnconfigured || config.postAuthPromptMode == PostAuthPromptMode.NONE)) {
            markFirstLoginComplete(config, user);
            context.success();
            return;
        }

        if (!meetsMinimum && availableUnconfigured.isEmpty()) {
            Response response = renderError(context,
                    "No available MFA methods to configure. Contact your administrator.");
            if (config.failIfSelectionInsufficient) {
                context.failureChallenge(AuthenticationFlowError.INTERNAL_ERROR, response);
            } else {
                context.challenge(response);
            }
            return;
        }

        Response challenge = renderForm(context, config, enabledMethods, configuredMethods, null, meetsMinimum);
        recordPrompt(user);
        context.challenge(challenge);
    }

    @Override
    public void action(AuthenticationFlowContext context) {
        Config config = Config.fromContext(context);
        UserModel user = context.getUser();
        RealmModel realm = context.getRealm();
        KeycloakSession session = context.getSession();
        Set<String> configuredMethods = resolveConfiguredMethods(session, realm, user);
        List<MfaMethod> enabledMethods = resolveEnabledMethods(config, realm);
        if (config.hideAlreadyConfiguredMethods) {
            enabledMethods = enabledMethods.stream()
                    .filter(method -> !configuredMethods.contains(method.id()))
                    .toList();
        }

        boolean meetsMinimum = meetsMinimum(config, enabledMethods, configuredMethods);
        MultivaluedMap<String, String> formData = context.getHttpRequest().getDecodedFormParameters();
        List<String> requestedMethods = Optional.ofNullable(formData.get("method")).orElse(List.of());
        boolean optOutRequested = "on".equalsIgnoreCase(formData.getFirst("optOut"));

        ValidationResult validation = validateSelection(config, enabledMethods, configuredMethods, requestedMethods,
                meetsMinimum);
        if (!validation.valid && config.failIfSelectionInsufficient) {
            Response challenge = renderForm(context, config, enabledMethods, configuredMethods, validation.message,
                    meetsMinimum);
            context.failureChallenge(AuthenticationFlowError.INVALID_USER, challenge);
            return;
        }

        if (!validation.valid) {
            markFirstLoginComplete(config, user);
            context.success();
            return;
        }

        boolean pushActionsToNextLogin = config.postAuthPromptMode == PostAuthPromptMode.NEXT_LOGIN_REQUIRED_ACTION
                && meetsMinimum;
        for (String methodId : validation.acceptedMethods) {
            enabledMethods.stream()
                    .filter(m -> m.id().equals(methodId))
                    .findFirst()
                    .ifPresent(method -> registerRequiredActions(context, method, pushActionsToNextLogin));
        }

        if (optOutRequested && config.allowUserOptOut && !validation.acceptedMethods.isEmpty()) {
            user.setSingleAttribute(config.optOutAttributeName, "true");
        }

        markFirstLoginComplete(config, user);
        context.success();
    }

    @Override
    public boolean requiresUser() {
        return true;
    }

    @Override
    public boolean configuredFor(KeycloakSession session, RealmModel realm, UserModel user) {
        return true;
    }

    @Override
    public void setRequiredActions(KeycloakSession session, RealmModel realm, UserModel user) {
        // No-op: required actions are attached dynamically during the flow.
    }

    @Override
    public void close() {
    }

    private boolean shouldSkipByExecution(Config config, AuthenticationFlowContext context) {
        AuthenticationExecutionModel execution = context.getExecution();
        if (execution.getRequirement() == AuthenticationExecutionModel.Requirement.DISABLED) {
            return true;
        }
        ClientModel client = context.getAuthenticationSession().getClient();
        if (!config.onlyForClients.isEmpty() && !config.onlyForClients.contains(client.getClientId())
                && !config.onlyForClients.contains(client.getId())) {
            return true;
        }
        if (!config.excludeClients.isEmpty() && (config.excludeClients.contains(client.getClientId())
                || config.excludeClients.contains(client.getId()))) {
            return true;
        }

        UserModel user = context.getUser();
        RealmModel realm = context.getRealm();
        if (!config.onlyForRoles.isEmpty()) {
            boolean hasRequiredRole = config.onlyForRoles.stream()
                    .map(realm::getRole)
                    .filter(Objects::nonNull)
                    .anyMatch(user::hasRole);
            if (!hasRequiredRole) {
                return true;
            }
        }
        if (!config.excludeRoles.isEmpty()) {
            boolean hasExcludedRole = config.excludeRoles.stream()
                    .map(realm::getRole)
                    .filter(Objects::nonNull)
                    .anyMatch(user::hasRole);
            if (hasExcludedRole) {
                return true;
            }
        }

        boolean isIdpLogin = context.getAuthenticationSession().getAuthNote("BROKER_SESSION_ID") != null;
        if (config.enforceForIdpUsers == EnforceForIdpUsers.NEVER && isIdpLogin) {
            return true;
        }
        if (config.enforceForIdpUsers == EnforceForIdpUsers.ONLY && !isIdpLogin) {
            return true;
        }

        if (config.enforceOnFirstLoginOnly) {
            String alreadyChecked = user.getFirstAttribute(ATTR_FIRST_LOGIN_COMPLETED);
            if (Boolean.parseBoolean(alreadyChecked)) {
                return true;
            }
        }

        if (!config.skipIfAttributeEquals.isEmpty()) {
            for (Map.Entry<String, String> entry : config.skipIfAttributeEquals.entrySet()) {
                String value = user.getFirstAttribute(entry.getKey());
                if (value != null && value.equals(entry.getValue())) {
                    return true;
                }
            }
        }

        return false;
    }

    private boolean shouldSkipByUserAttributes(Config config, AuthenticationFlowContext context, boolean meetsMinimum) {
        UserModel user = context.getUser();
        if (!config.allowUserOptOut) {
            return false;
        }
        String optOut = user.getFirstAttribute(config.optOutAttributeName);
        if (!Boolean.parseBoolean(optOut)) {
            return false;
        }
        if (config.optOutRespectedWhenNotSufficient) {
            return true;
        }
        return meetsMinimum;
    }

    private boolean shouldSkipByReminder(Config config, UserModel user) {
        if (config.remindEveryDays <= 0) {
            return false;
        }
        long now = System.currentTimeMillis();
        String lastPrompt = user.getFirstAttribute(ATTR_LAST_PROMPT);
        if (lastPrompt == null) {
            return false;
        }
        try {
            long last = Long.parseLong(lastPrompt);
            long threshold = now - Duration.ofDays(config.remindEveryDays).toMillis();
            return last > threshold;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private boolean shouldSkipByRollout(Config config, UserModel user, boolean meetsMinimum) {
        if (config.rolloutPercentage >= 100) {
            return false;
        }
        if (config.bypassRolloutIfNotSufficient && !meetsMinimum) {
            return false;
        }
        int bucket = switch (config.rolloutStrategy) {
            case HASH_USER_ID -> Math.abs(user.getId().hashCode()) % 100;
            case RANDOM -> ThreadLocalRandom.current().nextInt(100);
        };
        return bucket >= config.rolloutPercentage;
    }

    private boolean meetsMinimum(Config config, List<MfaMethod> enabledMethods, Set<String> configured) {
        long overall = configured.size();
        if (overall < config.minRequiredMfaMethods) {
            return false;
        }
        if (config.minRequiredFromList <= 0) {
            return true;
        }
        Set<String> enabledIds = new HashSet<>();
        for (MfaMethod method : enabledMethods) {
            enabledIds.add(method.id());
        }
        long fromList = configured.stream().filter(enabledIds::contains).count();
        return fromList >= config.minRequiredFromList;
    }

    private Set<String> resolveConfiguredMethods(KeycloakSession session, RealmModel realm, UserModel user) {
        Set<String> configured = new HashSet<>();
        SubjectCredentialManager credentialManager = user.credentialManager();
        credentialManager.getStoredCredentialsStream()
                .map(CredentialModel::getType)
                .forEach(type -> {
                    if ("otp".equals(type)) {
                        configured.add("totp");
                    } else if (WebAuthnCredentialModel.TYPE_TWOFACTOR.equals(type)) {
                        configured.add("webauthn");
                    } else if ("webauthn-passwordless".equals(type)) {
                        configured.add("webauthn_passwordless");
                    } else if ("recovery-authn-code".equals(type)) {
                        configured.add("recovery_codes");
                    } else if ("sms-otp".equals(type)) {
                        configured.add("sms_otp");
                    } else if ("email-otp".equals(type)) {
                        configured.add("email_otp");
                    } else if (type.startsWith("custom:")) {
                        configured.add(type);
                    }
                });
        return configured;
    }

    private List<MfaMethod> resolveEnabledMethods(Config config, RealmModel realm) {
        List<MfaMethod> all = defaultMethods();
        Map<String, MfaMethod> byId = new HashMap<>();
        for (MfaMethod method : all) {
            byId.put(method.id(), method);
        }
        List<MfaMethod> enabled = new ArrayList<>();
        for (String id : config.enabledMfaTypes) {
            MfaMethod method = byId.get(id);
            if (method != null) {
                if (!config.visibleOnlyIfSupported || method.isAvailable(realm)) {
                    enabled.add(method);
                }
            } else if (id.startsWith("custom:")) {
                MfaMethod custom = MfaMethod.custom(id, id.substring("custom:".length()));
                if (!config.visibleOnlyIfSupported || custom.isAvailable(realm)) {
                    enabled.add(custom);
                }
            }
        }
        return enabled;
    }

    private List<MfaMethod> defaultMethods() {
        List<MfaMethod> methods = new ArrayList<>();
        methods.add(new MfaMethod("totp", "Authenticator app (TOTP)",
                "Use an authenticator application to generate one-time codes.", "otp", List.of("CONFIGURE_TOTP")));
        methods.add(new MfaMethod("webauthn", "Security key / WebAuthn", "Register a WebAuthn security key.",
                WebAuthnCredentialModel.TYPE_TWOFACTOR, List.of("webauthn-register")));
        methods.add(new MfaMethod("webauthn_passwordless", "Passkey (passwordless)",
                "Register a passkey for passwordless login.", "webauthn-passwordless",
                List.of("webauthn-register-passwordless")));
        methods.add(new MfaMethod("recovery_codes", "Recovery codes", "Generate one-time recovery codes.",
                "recovery-authn-code", List.of("CONFIGURE_RECOVERY_AUTHN_CODES")));
        methods.add(new MfaMethod("sms_otp", "SMS one-time code", "Receive codes by SMS.", "sms-otp",
                List.of("sms-authenticator")));
        methods.add(new MfaMethod("email_otp", "Email one-time code", "Receive codes by email.", "email-otp",
                List.of("email-authenticator")));
        return methods;
    }

    private ValidationResult validateSelection(Config config,
            List<MfaMethod> enabledMethods,
            Set<String> configured,
            List<String> requested,
            boolean meetsMinimum) {
        Set<String> enabledIds = new HashSet<>();
        for (MfaMethod method : enabledMethods) {
            enabledIds.add(method.id());
        }
        List<String> selected = requested.stream()
                .filter(enabledIds::contains)
                .filter(id -> !configured.contains(id))
                .distinct()
                .toList();

        if (selected.isEmpty()) {
            if (meetsMinimum && config.allowNoSelectionIfAlreadySufficient) {
                return ValidationResult.valid(selected);
            }
            return ValidationResult.invalid("Select at least one method.");
        }

        SelectionMode mode = config.selectionMode;
        if (mode == SelectionMode.EXACTLY_ONE && selected.size() != 1) {
            return ValidationResult.invalid("Select exactly one method.");
        }
        if (mode == SelectionMode.ALL_UNCONFIGURED) {
            long unconfiguredCount = enabledIds.stream().filter(id -> !configured.contains(id)).count();
            if (selected.size() != unconfiguredCount) {
                return ValidationResult.invalid("You must select all unconfigured methods.");
            }
        }
        if (mode == SelectionMode.UP_TO_MAX && config.maxNewMethodsPerLogin > 0
                && selected.size() > config.maxNewMethodsPerLogin) {
            return ValidationResult.invalid("Select no more than " + config.maxNewMethodsPerLogin + " methods.");
        }
        return ValidationResult.valid(selected);
    }

    private void registerRequiredActions(AuthenticationFlowContext context, MfaMethod method, boolean nextLogin) {
        for (String action : method.requiredActions()) {
            if (nextLogin) {
                context.getUser().addRequiredAction(action);
            } else {
                context.getAuthenticationSession().addRequiredAction(action);
            }
        }
    }

    private Response renderForm(AuthenticationFlowContext context,
            Config config,
            List<MfaMethod> enabledMethods,
            Set<String> configuredMethods,
            String message,
            boolean meetsMinimum) {
        String title = meetsMinimum ? "Configure additional sign-in methods" : "Set up more sign-in protection";
        String description = meetsMinimum
                ? "You can add more MFA methods now for better recovery and flexibility."
                : "Your account needs additional multi-factor methods before continuing.";

        StringJoiner body = new StringJoiner("\n");
        body.add("<!DOCTYPE html>");
        body.add("<html lang=\"en\"><head><meta charset=\"UTF-8\"><title>MFA Enrollment</title>");
        body.add(
                "<style>body{font-family:Arial, sans-serif;background:#f7f7f9;padding:32px;}h1{margin-top:0;}fieldset{border:1px solid #d6d7dc;padding:16px;background:#fff;}label{display:flex;align-items:center;gap:8px;margin:8px 0;}small{color:#555;} .configured{color:green;font-weight:bold;} .error{color:#b30000;margin-bottom:12px;} .help{margin-bottom:12px;color:#333;} .cta{margin-top:16px;}</style>");
        body.add("</head><body>");
        body.add("<h1>" + escape(title) + "</h1>");
        body.add("<div class=\"help\">" + escape(description) + "</div>");
        if (message != null) {
            body.add("<div class=\"error\">" + escape(message) + "</div>");
        }
        body.add("<form method=\"post\">");
        body.add("<fieldset><legend>Available methods</legend>");

        boolean hasSelectable = false;
        for (MfaMethod method : enabledMethods) {
            boolean configured = configuredMethods.contains(method.id());
            boolean available = method.isAvailable(context.getRealm());
            if (configured && config.hideAlreadyConfiguredMethods) {
                continue;
            }
            String disabledAttr = (!available || configured) ? " disabled" : "";
            if (available && !configured) {
                hasSelectable = true;
            }
            body.add("<label><input type=\"checkbox\" name=\"method\" value=\"" + escape(method.id()) + "\""
                    + disabledAttr + " />");
            String status = configured ? "<span class=\"configured\">Configured</span>"
                    : (available ? "" : "<span class=\"configured\">Unavailable</span>");
            body.add("<div><div><strong>" + escape(method.label()) + "</strong> " + status + "</div>");
            body.add("<small>" + escape(method.description()) + "</small></div></label>");
        }
        if (!hasSelectable) {
            body.add("<div class=\"help\">No additional methods available.</div>");
        }
        body.add("</fieldset>");

        if (config.allowUserOptOut) {
            body.add(
                    "<label style=\"margin-top:12px;\"><input type=\"checkbox\" name=\"optOut\"/> Don't ask me again</label>");
        }
        body.add("<div class=\"cta\"><button type=\"submit\">Continue</button></div>");
        body.add("</form>");
        body.add("</body></html>");

        return Response.status(Response.Status.OK)
                .header("X-Frame-Options", "SAMEORIGIN")
                .entity(body.toString())
                .build();
    }

    private Response renderError(AuthenticationFlowContext context, String message) {
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html><head><meta charset=\"UTF-8\"><title>MFA Enrollment</title></head><body>");
        html.append("<h1>MFA Enrollment</h1>");
        html.append("<p>").append(escape(message)).append("</p>");
        html.append("</body></html>");
        return Response.status(Response.Status.BAD_REQUEST)
                .header("X-Frame-Options", "SAMEORIGIN")
                .entity(html.toString())
                .build();
    }

    private void recordPrompt(UserModel user) {
        user.setSingleAttribute(ATTR_LAST_PROMPT, String.valueOf(System.currentTimeMillis()));
    }

    private void markFirstLoginComplete(Config config, UserModel user) {
        if (config.enforceOnFirstLoginOnly) {
            user.setSingleAttribute(ATTR_FIRST_LOGIN_COMPLETED, "true");
        }
    }

    private String escape(String input) {
        if (input == null) {
            return "";
        }
        return input.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private enum SelectionMode {
        AT_LEAST_ONE, EXACTLY_ONE, ALL_UNCONFIGURED, UP_TO_MAX
    }

    private enum EnforceForIdpUsers {
        ALWAYS, NEVER, ONLY
    }

    private enum RolloutStrategy {
        HASH_USER_ID, RANDOM
    }

    private enum PostAuthPromptMode {
        SAME_LOGIN, NEXT_LOGIN_REQUIRED_ACTION, NONE
    }

    private record MfaMethod(String id, String label, String description, String credentialType,
            List<String> requiredActions) {
        boolean isAvailable(RealmModel realm) {
            for (String action : requiredActions) {
                if (realm.getRequiredActionProviderByAlias(action) == null) {
                    return false;
                }
            }
            return true;
        }

        static MfaMethod custom(String id, String alias) {
            List<String> actions = new ArrayList<>();
            actions.add(alias);
            return new MfaMethod(id, "Custom: " + alias, "Custom enrollment step: " + alias, id, actions);
        }
    }

    private record Config(
            int minRequiredMfaMethods,
            int minRequiredFromList,
            int maxAllowedMfaMethods,
            boolean enforceOnFirstLoginOnly,
            EnforceForIdpUsers enforceForIdpUsers,
            List<String> enabledMfaTypes,
            boolean visibleOnlyIfSupported,
            boolean hideAlreadyConfiguredMethods,
            SelectionMode selectionMode,
            int maxNewMethodsPerLogin,
            boolean failIfSelectionInsufficient,
            boolean allowNoSelectionIfAlreadySufficient,
            boolean offerConfigureAdditionalMethods,
            PostAuthPromptMode postAuthPromptMode,
            boolean allowUserOptOut,
            boolean optOutRespectedWhenNotSufficient,
            String optOutAttributeName,
            int rolloutPercentage,
            RolloutStrategy rolloutStrategy,
            boolean bypassRolloutIfNotSufficient,
            List<String> onlyForRoles,
            List<String> excludeRoles,
            List<String> onlyForClients,
            List<String> excludeClients,
            int remindEveryDays,
            Map<String, String> skipIfAttributeEquals) {
        static Config fromContext(AuthenticationFlowContext context) {
            AuthenticatorConfigModel model = context.getAuthenticatorConfig();
            Map<String, String> cfg = model != null ? model.getConfig() : Collections.emptyMap();
            return new Config(
                    parseInt(cfg.get("min_required_mfa_methods"), DEFAULT_MIN_REQUIRED),
                    parseInt(cfg.get("min_required_from_list"), DEFAULT_MIN_REQUIRED),
                    parseInt(cfg.get("max_allowed_mfa_methods"), 0),
                    parseBoolean(cfg.get("enforce_on_first_login_only"), false),
                    EnforceForIdpUsers
                            .valueOf(parseEnum(cfg.get("enforce_for_idp_users"), "always", "always").toUpperCase()),
                    parseList(cfg.get("enabled_mfa_types"), DEFAULT_ENABLED_TYPES),
                    parseBoolean(cfg.get("visible_only_if_supported"), true),
                    parseBoolean(cfg.get("hide_already_configured_methods"), false),
                    SelectionMode.valueOf(
                            parseEnum(cfg.get("selection_mode"), "at_least_one", "at_least_one").toUpperCase()),
                    parseInt(cfg.get("max_new_methods_per_login"), 0),
                    parseBoolean(cfg.get("fail_if_selection_insufficient"), true),
                    parseBoolean(cfg.get("allow_no_selection_if_already_sufficient"), true),
                    parseBoolean(cfg.get("offer_configure_additional_methods"), true),
                    PostAuthPromptMode.valueOf(
                            parseEnum(cfg.get("post_auth_prompt_mode"), "same_login", "same_login").toUpperCase()),
                    parseBoolean(cfg.get("allow_user_opt_out"), true),
                    parseBoolean(cfg.get("opt_out_respected_when_not_sufficient"), false),
                    cfg.getOrDefault("opt_out_attribute_name", "mfaEnrollment.skipFuturePrompts"),
                    parseInt(cfg.get("rollout_percentage"), 100),
                    RolloutStrategy.valueOf(
                            parseEnum(cfg.get("rollout_strategy"), "hash_user_id", "hash_user_id").toUpperCase()),
                    parseBoolean(cfg.get("bypass_rollout_if_not_sufficient"), true),
                    parseList(cfg.get("only_for_roles"), List.of()),
                    parseList(cfg.get("exclude_roles"), List.of()),
                    parseList(cfg.get("only_for_clients"), List.of()),
                    parseList(cfg.get("exclude_clients"), List.of()),
                    parseInt(cfg.get("remind_every_days"), 0),
                    parseKeyValueList(cfg.get("skip_if_attribute_equals")));
        }

        private static boolean parseBoolean(String value, boolean defaultValue) {
            if (value == null) {
                return defaultValue;
            }
            return Boolean.parseBoolean(value);
        }

        private static int parseInt(String value, int defaultValue) {
            try {
                return value == null ? defaultValue : Integer.parseInt(value);
            } catch (NumberFormatException ex) {
                return defaultValue;
            }
        }

        private static List<String> parseList(String raw, List<String> defaultValue) {
            if (raw == null || raw.isBlank()) {
                return defaultValue;
            }
            String[] split = raw.split("[,\\n]");
            List<String> result = new ArrayList<>();
            for (String part : split) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    result.add(trimmed);
                }
            }
            return result.isEmpty() ? defaultValue : result;
        }

        private static Map<String, String> parseKeyValueList(String raw) {
            Map<String, String> map = new HashMap<>();
            if (raw == null || raw.isBlank()) {
                return map;
            }
            String[] split = raw.split("[,\\n]");
            for (String entry : split) {
                String[] kv = entry.split("=", 2);
                if (kv.length == 2) {
                    map.put(kv[0].trim(), kv[1].trim());
                }
            }
            return map;
        }

        private static String parseEnum(String raw, String defaultValue, String fallback) {
            if (raw == null) {
                return defaultValue;
            }
            String normalized = raw.trim().toLowerCase();
            if (normalized.isEmpty()) {
                return fallback;
            }
            return normalized;
        }
    }

    private record ValidationResult(boolean valid, List<String> acceptedMethods, String message) {
        static ValidationResult valid(List<String> acceptedMethods) {
            return new ValidationResult(true, acceptedMethods, null);
        }

        static ValidationResult invalid(String message) {
            return new ValidationResult(false, List.of(), message);
        }
    }
}
