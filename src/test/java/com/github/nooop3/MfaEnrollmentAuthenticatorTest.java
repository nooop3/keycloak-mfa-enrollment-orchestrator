package com.github.nooop3;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.AuthenticationFlowError;
import org.keycloak.forms.login.LoginFormsProvider;
import org.keycloak.models.AuthenticationExecutionModel;
import org.keycloak.models.AuthenticatorConfigModel;
import org.keycloak.models.ClientModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.SubjectCredentialManager;
import org.keycloak.sessions.AuthenticationSessionModel;
import org.keycloak.http.HttpRequest;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MfaEnrollmentAuthenticatorTest {

    @Mock
    private AuthenticationFlowContext context;
    @Mock
    private KeycloakSession session;
    @Mock
    private RealmModel realm;
    @Mock
    private UserModel user;
    @Mock
    private AuthenticationExecutionModel execution;
    @Mock
    private AuthenticationSessionModel authSession;
    @Mock
    private ClientModel client;
    @Mock
    private AuthenticatorConfigModel configModel;
    @Mock
    private HttpRequest httpRequest;
    @Mock
    private SubjectCredentialManager credentialManager;
    @Mock
    private LoginFormsProvider loginFormsProvider;

    private MfaEnrollmentAuthenticator authenticator;
    private Map<String, String> config;

    @BeforeEach
    void setUp() {
        authenticator = new MfaEnrollmentAuthenticator();
        config = new HashMap<>();

        lenient().when(context.getSession()).thenReturn(session);
        lenient().when(context.getRealm()).thenReturn(realm);
        lenient().when(context.getUser()).thenReturn(user);
        lenient().when(context.getExecution()).thenReturn(execution);
        lenient().when(context.getAuthenticationSession()).thenReturn(authSession);
        lenient().when(authSession.getClient()).thenReturn(client);
        lenient().when(context.getAuthenticatorConfig()).thenReturn(configModel);
        lenient().when(configModel.getConfig()).thenReturn(config);
        lenient().when(user.credentialManager()).thenReturn(credentialManager);
        lenient().when(credentialManager.getStoredCredentialsStream()).thenReturn(Stream.empty());
        lenient().when(context.getHttpRequest()).thenReturn(httpRequest);
        lenient().when(context.form()).thenReturn(loginFormsProvider);
        lenient().when(loginFormsProvider.setAttribute(anyString(), any())).thenReturn(loginFormsProvider);
        lenient().when(loginFormsProvider.createForm(anyString()))
                .thenAnswer(inv -> Response.status(Response.Status.OK)
                        .entity("template:" + inv.getArgument(0, String.class))
                        .build());

        // Make sure default methods are available
        lenient().when(realm.getRequiredActionProviderByAlias(anyString()))
                .thenReturn(mock(org.keycloak.models.RequiredActionProviderModel.class));
    }

    @Test
    void testAuthenticate_SkipByExecutionDisabled() {
        when(execution.getRequirement()).thenReturn(AuthenticationExecutionModel.Requirement.DISABLED);
        authenticator.authenticate(context);
        verify(context).success();
    }

    @Test
    void testAuthenticate_SkipByClient() {
        config.put("only_for_clients", "other-client");
        when(client.getClientId()).thenReturn("my-client");
        when(client.getId()).thenReturn("my-client-id");

        authenticator.authenticate(context);
        verify(context).success();
    }

    @Test
    void testAuthenticate_SkipByFirstLoginCompleted() {
        config.put("enforce_on_first_login_only", "true");
        when(user.getFirstAttribute("mfa.firstLoginCompleted")).thenReturn("true");

        authenticator.authenticate(context);
        verify(context).success();
    }

    @Test
    void testAuthenticate_ShowForm_NotEnoughMfa() {
        // Default config requires 1 MFA method
        // User has none

        authenticator.authenticate(context);

        ArgumentCaptor<Response> responseCaptor = ArgumentCaptor.forClass(Response.class);
        verify(context).challenge(responseCaptor.capture());

        Response response = responseCaptor.getValue();
        assertEquals(200, response.getStatus());
        assertEquals("template:mfa-enrollment.ftl", response.getEntity().toString());
        verify(loginFormsProvider).createForm("mfa-enrollment.ftl");
    }

    @Test
    void testAuthenticate_Success_MeetsMinimum() {
        // User has TOTP
        when(credentialManager.getStoredCredentialsStream()).thenAnswer(i -> {
            var cred = mock(org.keycloak.credential.CredentialModel.class);
            when(cred.getType()).thenReturn("otp");
            return Stream.of(cred);
        });

        // Config: offer_configure_additional_methods = false
        config.put("offer_configure_additional_methods", "false");

        authenticator.authenticate(context);
        verify(context).success();
    }

    @Test
    void testAction_SelectMethod() {
        // Enable TOTP
        config.put("enabled_mfa_types", "totp");

        MultivaluedMap<String, String> formData = new MultivaluedHashMap<>();
        formData.add("method", "totp");
        when(httpRequest.getDecodedFormParameters()).thenReturn(formData);

        // Mock realm to have required action
        when(realm.getRequiredActionProviderByAlias("CONFIGURE_TOTP"))
                .thenReturn(mock(org.keycloak.models.RequiredActionProviderModel.class));

        authenticator.action(context);

        verify(authSession).addRequiredAction("CONFIGURE_TOTP");
        verify(context).success();
    }

    @Test
    void testAction_InvalidSelection() {
        config.put("enabled_mfa_types", "totp");

        MultivaluedMap<String, String> formData = new MultivaluedHashMap<>();
        // No method selected
        when(httpRequest.getDecodedFormParameters()).thenReturn(formData);

        authenticator.action(context);

        verify(context).failureChallenge(eq(AuthenticationFlowError.INVALID_USER), any(Response.class));
    }

    @Test
    void testAction_OptOut() {
        config.put("allow_user_opt_out", "true");
        // User meets minimum (0 required for this test to simplify)
        config.put("min_required_mfa_methods", "0");
        config.put("min_required_from_list", "0");

        MultivaluedMap<String, String> formData = new MultivaluedHashMap<>();
        formData.add("optOut", "on");
        when(httpRequest.getDecodedFormParameters()).thenReturn(formData);

        authenticator.action(context);

        verify(user).setSingleAttribute("mfaEnrollment.skipFuturePrompts", "true");
        verify(context).success();
    }
}
