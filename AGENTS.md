# Configurable Multi-Factor Enrollment Authenticator

This document describes the design and behavior of a new Keycloak authenticator provider that guides users through configuring additional two-factor (MFA) methods in a highly configurable way.

The authenticator is intended to be used inside a post-login or browser flow, typically behind a conditional execution (e.g. “Enforce MFA” or “User without sufficient MFA”) so that administrators can control exactly when and for whom it runs.

## Goals

1. Allow admins to define **when** the authenticator runs (e.g. only if the user has no 2FA configured, or less than N methods).
2. Allow users to **choose freely** which 2FA method(s) to configure, from an admin-configured list (TOTP, WebAuthn, passkeys, recovery codes, etc.).
3. Allow admins to control **how many** methods the user must configure (at least one, exactly N, “all listed”, etc.).
4. Allow users to:
   - configure one or more new methods during login;
   - optionally **continue configuring** additional methods after success.
5. Allow users to **opt-out** from future prompts (“don’t show again”), stored as a per-user flag.
6. Support a **percentage-based rollout** so that only some users are prompted.
7. Make the logic as configurable as possible while keeping behavior understandable.

---

## High-Level Behavior

When the execution is triggered, the authenticator:

1. Evaluates **pre-conditions** based on admin configuration and user state (MFA methods already configured, user attributes, client, etc.).
2. If conditions are not met (e.g. user already has enough methods or opted out), it **SKIPs** and the flow continues.
3. If conditions are met:
   - Builds a list of **allowed MFA types** based on admin configuration.
   - Checks which of these are **already configured** for the user.
   - Shows a UI where the user can:
     - See which methods are configured vs not configured.
     - Select one or more new methods to configure.
     - Optionally select “don’t show this again”.
4. After the user selects methods:
   - Validates that selection satisfies admin-configured rules (e.g. “at least 1 new method”, “must choose all unconfigured methods”, etc.).
   - For each selected method, creates or triggers the appropriate **Required Action(s)** (e.g. `CONFIGURE_TOTP`, `webauthn-register`, `CONFIGURE_RECOVERY_AUTHN_CODES`, etc.).
5. When the user **returns** from Required Actions:
   - Re-evaluates method counts.
   - If still below required threshold and the admin requires strict enforcement, the user is prompted again or the login **fails** according to config.
   - If the user now meets the requirements:
     - Optionally shows a “configure more methods” screen (post-MFA continuation).
     - Respects “don’t show again” and rollout percentage for future logins.

---

## Configuration Options (Admin)

### 1. Triggering Conditions

These options control **when** the authenticator actually prompts the user.

- **`min_required_mfa_methods` (integer, default: 1)**
  Minimum number of distinct MFA methods a user must have configured overall (across all supported types). If the user has at least this many, the authenticator **skips**.

- **`min_required_from_list` (integer, default: 1)**
  Minimum number of methods from the *admin-configured list* that the user must have. This allows you to enforce “at least one of [TOTP, WebAuthn]” even if the user has some other method elsewhere.

- **`max_allowed_mfa_methods` (integer, optional)**
  Upper bound of methods considered. If provided and user already has ≥ this number, the authenticator will skip instead of pushing more enrollment.

- **`enforce_on_first_login_only` (boolean, default: false)**
  When enabled, runs only on the user’s **first successful login** (tracked via a user attribute like `mfa.firstLoginCompleted`).

- **`enforce_for_idp_users` (enum: `always`, `never`, `only`, default: `always`)**
  Controls behavior when the user came from a brokered IdP login:
  - `always` – treat IdP and local logins the same.
  - `never` – skip for IdP logins (assume upstream MFA).
  - `only` – **only** apply to IdP logins (e.g. you use this authenticator to add local MFA on top of SSO).

---

### 2. Supported MFA Methods

These options define which methods the **user can see and select**.

- **`enabled_mfa_types` (multivalued list of enums)**
  Possible values (depending on Keycloak version and your realm setup):
  - `totp` – Time-based OTP (built-in `CONFIGURE_TOTP`).
  - `webauthn` – WebAuthn security key (username + password + WebAuthn).
  - `webauthn_passwordless` – WebAuthn passkeys / passwordless.
  - `recovery_codes` – Recovery authentication codes.
  - `sms_otp` – SMS-based OTP (if you have a plugin / authenticator for it).
  - `email_otp` – Email OTP.
  - `custom:<id>` – Any custom Required Action or authenticator alias.

  The provider will:
  - Map each enum to a human-readable label and description.
  - Map each enum to required actions and/or subflows.

- **`visible_only_if_supported` (boolean, default: true)**
  If true, a method is only shown if the backing authenticator/required-action is actually available and enabled in the realm.

- **`hide_already_configured_methods` (boolean, default: false)**
  If true, methods user already configured are **not** shown in the list.
  If false, they are shown but marked as “configured”.

---

### 3. User Selection Rules

These options define **what the user must choose** for the login to proceed.

- **`selection_mode` (enum: `at_least_one`, `exactly_one`, `all_unconfigured`, `up_to_max`, default: `at_least_one`)**

  - `at_least_one` – user must select at least one **new** method.
  - `exactly_one` – user must select exactly one method.
  - `all_unconfigured` – user must select all methods from the list that are not yet configured.
  - `up_to_max` – combined with `max_new_methods_per_login` (see below).

- **`max_new_methods_per_login` (integer, default: unset)**
  Maximum number of new methods user can start configuring in a single login (only used when `selection_mode = up_to_max`).

- **`fail_if_selection_insufficient` (boolean, default: true)**
  If true, and the user’s selection doesn’t satisfy the rules (e.g. selecting 0 when `at_least_one` is required), the authenticator **fails** (login error).
  If false, the authenticator marks itself as “SKIP with warning” – user continues the flow but does not gain additional methods (useful if you only want soft prompting).

- **`allow_no_selection_if_already_sufficient` (boolean, default: true)**
  If user already meets `min_required_mfa_methods` or `min_required_from_list`, and selects no new methods, allow login to proceed.

---

### 4. Post-Authentication Behavior

Letting users continue configuring other methods after login.

- **`offer_configure_additional_methods` (boolean, default: true)**  
  After user successfully configures one MFA and meets the min requirements, show a small screen:
  - “You’ve successfully set up TOTP. Do you want to configure additional methods now?”
  - Display remaining unconfigured methods.
  - Provide “Configure now” and “Skip for now” actions.

- **`post_auth_prompt_mode` (enum: `same_login`, `next_login_required_action`, `none`, default: `same_login`)**
  - `same_login` – user continues configuring in the same login session.
  - `next_login_required_action` – schedule Required Actions and let user complete them on next login.
  - `none` – don’t offer additional configuration beyond minimally required methods.

---

### 5. “Don’t Show Again” / User Opt-Out

- **`allow_user_opt_out` (boolean, default: true)**  
  Shows a checkbox such as “Don’t ask me to configure more MFA methods again”.

  When user checks this:
  - Authenticator sets a user attribute, e.g.
    `mfaEnrollment.skipFuturePrompts = "true"`.
  - Future executions will **skip** if this attribute is set, unless overridden.

- **`opt_out_respected_when_not_sufficient` (boolean, default: false)**
  If false (strict mode), opt-out is ignored when the user **does not meet** `min_required_mfa_methods`/`min_required_from_list`.
  If true, opt-out is respected even if the user has fewer methods than desired (useful for very soft recommendations).

- **`opt_out_attribute_name` (string, default: `mfaEnrollment.skipFuturePrompts`)**
  Allows admins to customize or share the flag with other authenticators.

---

### 6. Percentage-Based Rollout

- **`rollout_percentage` (int, 0–100, default: 100)**  
  Probability that a user will see the authenticator, assuming all other conditions are met.

- **`rollout_strategy` (enum: `random`, `hash_user_id`, default: `hash_user_id`)**

  - `random` – each login, generate a random number 0–99. If `< rollout_percentage`, prompt user.
  - `hash_user_id` – compute a stable hash of the user ID (or username/email), normalize to 0–99, and compare to `rollout_percentage`. This ensures that the **same** users are consistently included/excluded.

- **`bypass_rollout_if_not_sufficient` (boolean, default: true)**
  If true, users who don’t meet `min_required_mfa_methods` are prompted regardless of percentage. Percentage only applies to users who already meet minimum but are candidates for “more MFA”.

---

### 7. Advanced Logic / Targeting

These options let admins fine-tune **who** is targeted.

- **`only_for_roles` (multivalued list of realm roles, optional)**
  If set, authenticator only runs for users having at least one of these roles.

- **`exclude_roles` (multivalued list of realm roles, optional)**
  If user has any of these roles, authenticator skips.

- **`only_for_clients` (multivalued list of client IDs/aliases, optional)**
  If set, authenticator runs only when login is for one of these clients.

- **`exclude_clients` (multivalued list of client IDs/aliases, optional)**
  If current client is in this list, authenticator skips.

- **`remind_every_days` (integer, optional)**
  If set, authenticator will only prompt again if the last time it prompted the user (stored as e.g. `mfaEnrollment.lastPrompt`) is older than this many days.

- **`skip_if_attribute_equals` (key=value list, optional)**
  For example:
  - `securityLevel=low`
  - `mfaEnrollment.skip=true`
  If any configured user attribute equals configured value, authenticator skips.

---

## Execution Flow (Pseudo-Code)

**Pre-processing:**

1. Determine context (user, client, isIdPLogin, etc.).
2. If `only_for_roles` configured and user has none → SKIP.
3. If `exclude_roles` configured and user has any → SKIP.
4. If `only_for_clients` configured and current client not in list → SKIP.
5. If `exclude_clients` configured and current client in list → SKIP.
6. If `enforce_for_idp_users` is `never` and this is IdP login → SKIP.
7. If `enforce_for_idp_users` is `only` and this is not IdP login → SKIP.
8. If `allow_user_opt_out` and `opt_out_attribute_name == "true"`:
   - If `opt_out_respected_when_not_sufficient` or user already has ≥ min methods → SKIP.
9. If `remind_every_days` configured and `lastPrompt` < threshold → SKIP.
10. Apply rollout:
    - Compute `bucket` (random or hash); if `bucket ≥ rollout_percentage` and `bypass_rollout_if_not_sufficient` is false or user already sufficient → SKIP.

**Enrollment decision:**

1. Gather user’s currently configured MFA methods.
2. If user meets `min_required_mfa_methods` AND `min_required_from_list`:
    - If `offer_configure_additional_methods` is false → SKIP.
    - Else show “configure more methods?” screen, or skip based on percentage.

3. If user does not meet requirements:
    - Build list of **unconfigured** methods from `enabled_mfa_types` (respect visibility settings).
    - If list is empty:
      - Either SKIP (nothing to offer) or FAIL based on config (strict).
    - Render selector screen with selection rules.

**User interaction:**

1. User selects set of methods `S` and optionally toggles “don’t show again”.
2. Validate `S` against `selection_mode`, `min_required_from_list`, and `max_new_methods_per_login`.
    - If invalid and `fail_if_selection_insufficient` → FAIL.
    - If invalid and `fail_if_selection_insufficient = false` → SKIP (soft fail).
3. For each method `m ∈ S`, register relevant Required Action(s) on the authentication session.
4. If “don’t show again” selected:
    - Set `opt_out_attribute_name = "true"` on user after successful enrollment (not before).

**Return from Required Actions:**

1. On next pass (same login or next login based on `post_auth_prompt_mode`), recompute configured MFA methods.
2. If still insufficient:
    - Either prompt again or FAIL, depending on configuration.
3. If sufficient:
    - If `offer_configure_additional_methods` enabled and there are still unconfigured methods:
      - Show “configure more?” screen.
    - Otherwise SUCCESS and continue flow.

---

## Example Configurations

### A. Strict Rollout: Enforce at Least One MFA, No Opt-Out

- `min_required_mfa_methods = 1`
- `enabled_mfa_types = [totp, webauthn, webauthn_passwordless, recovery_codes]`
- `selection_mode = at_least_one`
- `fail_if_selection_insufficient = true`
- `allow_user_opt_out = false`
- `rollout_percentage = 100`

Effect:
Any user without at least one of the configured methods must enroll at least one or login will fail. No opt-out, full enforcement.

---

### B. Soft Rollout: Offer Extra MFA to 20% of Users

- `min_required_mfa_methods = 1`
- `enabled_mfa_types = [webauthn, recovery_codes]`
- `rollout_percentage = 20`
- `rollout_strategy = hash_user_id`
- `bypass_rollout_if_not_sufficient = false`
- `allow_user_opt_out = true`
- `opt_out_respected_when_not_sufficient = true`
- `selection_mode = at_least_one`
- `fail_if_selection_insufficient = false`

Effect:
Users who already have basic MFA might occasionally be asked to add WebAuthn or recovery codes. They can skip or opt out, and login will not fail because of this authenticator.

---

### C. Security-Sensitive Clients Only

- `min_required_mfa_methods = 2`
- `enabled_mfa_types = [totp, webauthn, webauthn_passwordless]`
- `selection_mode = at_least_one`
- `only_for_clients = [ "admin-portal", "prod-dashboard" ]`
- `only_for_roles = [ "admin", "ops" ]`

Effect:
Only admins/ops accessing critical clients are forced to maintain at least two MFA methods (e.g. TOTP + WebAuthn). Other users/clients are not affected.

---

## UI Considerations

The authenticator’s UI should:

- Clearly **distinguish** between:
  - methods already configured (with some indication: “Configured ✓”),
  - methods recommended by the administrator (highlighted),
  - methods not available due to missing realm configuration.
- Explain **why** the user is seeing the prompt (e.g. “Your account needs at least one additional sign-in method”).
- Explain the effect of “Don’t show this again” in simple language.
- Show the **minimum requirement** and mark invalid selections with clear error messages:
  - “Please select at least one additional method.”
  - “You must configure all listed methods to continue.”

---

## Implementation Notes

- The provider will implement `Authenticator` and `AuthenticatorFactory` with config properties matching the options above.
- Detection of already configured methods should rely on:
  - existing Keycloak APIs for credential types (e.g. `CredentialModel`).
  - user attributes / credential types for WebAuthn, recovery codes, etc.
- Required Actions are attached via `AuthenticationSessionModel.addRequiredAction(...)`.
- The “don’t show again” / “last prompt” behavior should be implemented via user attributes to keep it simple and transparent.

---

This authenticator is designed to be a **flexible MFA enrollment orchestrator**: admins get fine-grained control over *when* and *how* users are asked to configure additional MFA, and users get maximum freedom in **which methods** they choose, while still allowing strong policies where needed.
