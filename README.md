# Configurable Multi-Factor Enrollment Authenticator

A Keycloak authenticator provider that guides users through configuring additional MFA methods in a highly configurable way. It is intended for use inside a post-login or browser flow, typically behind a conditional execution (e.g. “Enforce MFA” or “User without sufficient MFA”) so administrators control exactly when and for whom it runs.

## What It Solves

- Enforce when MFA enrollment prompts appear (first login only, IdP users only/never, client or role targeting, reminder cadence).
- Let users choose which 2FA methods to configure from an admin-defined list (TOTP, WebAuthn, passkeys, recovery codes, SMS/Email OTP, custom required actions).
- Control how many methods must be configured (minimums, exact counts, all listed, or capped per login).
- Allow opt-out with a per-user flag plus optional “remind me later” thresholds.
- Support percentage-based rollout (stable hash or random) with bypass rules for insufficiently protected accounts.
- Offer a post-auth prompt to configure more methods after the minimum is met.

## High-Level Flow

1. Pre-checks: roles/clients filters, IdP policy, opt-out flag, reminder window, rollout bucketing.
2. Determine already configured MFA methods and compare to minimum requirements.
3. If insufficient, render a selection UI with admin-defined rules; otherwise optionally invite the user to add more.
4. Validate the user’s selections against `selection_mode` and limits; fail, skip, or continue per config.
5. Attach required actions for each chosen method (e.g. `CONFIGURE_TOTP`, `webauthn-register`, `CONFIGURE_RECOVERY_AUTHN_CODES`, or custom).
6. After required actions complete, re-evaluate counts; either prompt again, fail, or finish. Respect opt-out and rollout flags for future logins.

## Configuration Reference

### Triggering Conditions

- `min_required_mfa_methods` (int, default 1): Minimum distinct MFA methods overall before skipping.
- `min_required_from_list` (int, default 1): Minimum methods from the configured list the user must have.
- `max_allowed_mfa_methods` (int, optional): Skip if user already has this many or more.
- `enforce_on_first_login_only` (bool, default false): Only run on the user’s first successful login.
- `enforce_for_idp_users` (`always`|`never`|`only`, default `always`): Behavior for brokered IdP logins.

### Supported MFA Methods

- `enabled_mfa_types` (list): `totp`, `webauthn`, `webauthn_passwordless`, `recovery_codes`, `sms_otp`, `email_otp`, `custom:<id>`.
- `visible_only_if_supported` (bool, default true): Hide methods lacking backing required action/authenticator.
- `hide_already_configured_methods` (bool, default false): Do not show methods the user already configured.

### User Selection Rules

- `selection_mode` (`at_least_one`|`exactly_one`|`all_unconfigured`|`up_to_max`, default `at_least_one`).
- `max_new_methods_per_login` (int, optional): Cap when using `up_to_max`.
- `fail_if_selection_insufficient` (bool, default true): Fail login vs soft-skip on invalid selection.
- `allow_no_selection_if_already_sufficient` (bool, default true): Let users proceed if they already meet minimums.

### Post-Authentication

- `offer_configure_additional_methods` (bool, default true): Invite users to add more after minimum is met.
- `post_auth_prompt_mode` (`same_login`|`next_login_required_action`|`none`, default `same_login`).

### Opt-Out

- `allow_user_opt_out` (bool, default true): Show “don’t ask again” checkbox.
- `opt_out_respected_when_not_sufficient` (bool, default false): Honor opt-out even if user is under-secured.
- `opt_out_attribute_name` (string, default `mfaEnrollment.skipFuturePrompts`): User attribute to persist opt-out.

### Percentage-Based Rollout

- `rollout_percentage` (0–100, default 100): Inclusion rate.
- `rollout_strategy` (`random`|`hash_user_id`, default `hash_user_id`): Stable vs per-login bucketing.
- `bypass_rollout_if_not_sufficient` (bool, default true): Always prompt under-protected users regardless of percentage.

### Targeting and Reminders

- `only_for_roles` / `exclude_roles` (list): Restrict or skip based on realm roles.
- `only_for_clients` / `exclude_clients` (list): Restrict or skip based on client ID/alias.
- `remind_every_days` (int, optional): Minimum days between prompts.
- `skip_if_attribute_equals` (key=value list): Skip if user attribute matches any entry.

## UI Guidelines

- Clearly separate configured vs unconfigured methods; highlight recommended methods; indicate unavailable ones.
- Explain why the user is seeing the prompt and what the minimum requirement is.
- Show concise errors for invalid selections (e.g. “Select at least one additional method”).
- Provide opt-out text that explains its effect in plain language.

## Example Configurations

### Strict (must enroll, no opt-out)

- `min_required_mfa_methods = 1`
- `enabled_mfa_types = [totp, webauthn, webauthn_passwordless, recovery_codes]`
- `selection_mode = at_least_one`
- `fail_if_selection_insufficient = true`
- `allow_user_opt_out = false`
- `rollout_percentage = 100`

### Soft rollout (20% nudge for extra MFA)

- `min_required_mfa_methods = 1`
- `enabled_mfa_types = [webauthn, recovery_codes]`
- `rollout_percentage = 20`
- `rollout_strategy = hash_user_id`
- `bypass_rollout_if_not_sufficient = false`
- `allow_user_opt_out = true`
- `opt_out_respected_when_not_sufficient = true`
- `selection_mode = at_least_one`
- `fail_if_selection_insufficient = false`

### Sensitive clients only

- `min_required_mfa_methods = 2`
- `enabled_mfa_types = [totp, webauthn, webauthn_passwordless]`
- `selection_mode = at_least_one`
- `only_for_clients = ["admin-portal", "prod-dashboard"]`
- `only_for_roles = ["admin", "ops"]`

## Implementation Notes

- Implements a Keycloak `Authenticator` and `AuthenticatorFactory` with the configuration options above.
- Detects configured methods via Keycloak credential APIs and any required user attributes.
- Adds required actions through `AuthenticationSessionModel.addRequiredAction(...)` for each selected method.
- Stores opt-out and last-prompt metadata in user attributes (e.g. `mfaEnrollment.skipFuturePrompts`, `mfaEnrollment.lastPrompt`).

## Development Status

This repository currently contains the design and requirements for the authenticator. Implementation, packaging, and deployment steps will be added alongside the code.

## Continuous Integration & Releases

GitLab CI/CD is configured in `.gitlab-ci.yml` to automatically:

- run `mvn verify` for merge request and branch pipelines;
- package the authenticator JAR and expose it as a job artifact;
- publish a GitLab Release (with the compiled JAR attached) whenever a Git tag is pushed.

To cut a release:

1. Update the project version/notes as needed.
2. Push a tag (for example `git tag -a v1.0.0 -m "v1.0.0"` followed by `git push origin v1.0.0`).
3. GitLab will run the pipeline, build the JAR, upload it to project uploads, and create a Release named after the tag with a direct download link to the artifact.
