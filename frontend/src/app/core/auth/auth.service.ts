import { Injectable } from '@angular/core';
import {
  CognitoUserPool,
  CognitoUser,
  AuthenticationDetails,
  CognitoUserSession,
} from 'amazon-cognito-identity-js';
import { Observable } from 'rxjs';
import { environment } from '../../../environment/environment';

// ── Types ─────────────────────────────────────────────────────────────────────

/**
 * What login() resolves to:
 *  - 'success'             → tokens stored, navigate to /dashboard
 *  - 'newPasswordRequired' → first login, navigate to /auth/new-password
 */
export type LoginResult = 'success' | 'newPasswordRequired';

/** Decoded subset of the Cognito ID token payload. */
export interface CognitoTokenPayload {
  sub:              string;   // userId — matches DynamoDB userId / backend sub
  email:            string;
  'cognito:groups': string[]; // ['ADMIN'] or ['USER']
}

// ── Service ───────────────────────────────────────────────────────────────────

/**
 * AuthService — single source of truth for Cognito authentication.
 *
 * Responsibilities:
 *  - login()               Sign in with SRP (no plaintext password on the wire)
 *  - completeNewPassword() Complete the NEW_PASSWORD_REQUIRED challenge (first login)
 *  - changePassword()      Change password for an already-authenticated user
 *  - logout()              Sign out and wipe sessionStorage
 *  - isAuthenticated()     Observable<boolean> — used by AuthGuard
 *  - getIdToken()          Synchronous read of the ID token — used by AuthInterceptor
 *  - getTokenPayload()     Decoded ID token claims (sub, email, groups)
 *
 * Token storage: sessionStorage (cleared when the browser tab closes).
 * The SDK stores keys as:
 *   CognitoIdentityServiceProvider.<clientId>.LastAuthUser
 *   CognitoIdentityServiceProvider.<clientId>.<username>.idToken
 */
@Injectable({ providedIn: 'root' })
export class AuthService {

  private readonly userPool: CognitoUserPool;

  /**
   * Holds the CognitoUser object across the NEW_PASSWORD_REQUIRED challenge.
   * Set by login() when Cognito returns the challenge; cleared after
   * completeNewPassword() succeeds or the user navigates away.
   */
  private pendingChallengeUser: CognitoUser | null = null;

  constructor() {
    this.userPool = new CognitoUserPool({
      UserPoolId: environment.cognito.userPoolId,
      ClientId:   environment.cognito.clientId,
      Storage:    window.sessionStorage,   // ← tab-scoped; cleared on close
    });
  }

  // ── Login ──────────────────────────────────────────────────────────────────

  /**
   * Authenticates the user via Cognito USER_SRP_AUTH.
   *
   * SRP (Secure Remote Password) means the password is never sent in plaintext —
   * the browser and Cognito exchange a cryptographic proof instead.
   *
   * Resolves with:
   *  - 'success'             tokens stored in sessionStorage → go to /dashboard
   *  - 'newPasswordRequired' first login → go to /auth/new-password
   *
   * Rejects with the Cognito error (NotAuthorizedException, UserNotFoundException, …).
   */
  login(email: string, password: string): Observable<LoginResult> {
    return new Observable(observer => {
      const authDetails = new AuthenticationDetails({
        Username: email,
        Password: password,
      });

      const cognitoUser = new CognitoUser({
        Username: email,
        Pool:     this.userPool,
        Storage:  window.sessionStorage,
      });

      cognitoUser.authenticateUser(authDetails, {
        onSuccess: (_session: CognitoUserSession) => {
          // Tokens are already written to sessionStorage by the SDK.
          this.pendingChallengeUser = null;
          observer.next('success');
          observer.complete();
        },

        onFailure: (err: Error) => {
          observer.error(err);
        },

        // Cognito triggers this on the very first login after AdminCreateUser.
        // The user must set a permanent password before they can proceed.
        newPasswordRequired: (_userAttributes: object, _requiredAttributes: object) => {
          this.pendingChallengeUser = cognitoUser;
          observer.next('newPasswordRequired');
          observer.complete();
        },
      });
    });
  }

  // ── Complete New Password Challenge ───────────────────────────────────────

  /**
   * Completes the NEW_PASSWORD_REQUIRED challenge after first login.
   *
   * Must be called on the /auth/new-password page, which is only reachable
   * when pendingChallengeUser is set (enforced by ChallengeGuard).
   */
  completeNewPassword(newPassword: string): Observable<void> {
    return new Observable(observer => {
      if (!this.pendingChallengeUser) {
        observer.error(new Error('No pending challenge. Please log in again.'));
        return;
      }

      this.pendingChallengeUser.completeNewPasswordChallenge(
        newPassword,
        {},   // no additional user attributes to set
        {
          onSuccess: (_session: CognitoUserSession) => {
            this.pendingChallengeUser = null;
            observer.next();
            observer.complete();
          },
          onFailure: (err: Error) => {
            observer.error(err);
          },
        },
      );
    });
  }

  // ── Change Password (settings page) ──────────────────────────────────────

  /**
   * Changes the password for the currently signed-in user.
   * Used by the Password Management settings page.
   *
   * Rejects with:
   *  - NotAuthorizedException  → current password is wrong
   *  - InvalidPasswordException → new password doesn't meet Cognito policy
   */
  changePassword(currentPassword: string, newPassword: string): Observable<void> {
    return new Observable(observer => {
      const cognitoUser = this.userPool.getCurrentUser();

      if (!cognitoUser) {
        observer.error(new Error('No authenticated user found. Please log in again.'));
        return;
      }

      // getSession() also silently refreshes expired access/id tokens if the
      // refresh token is still valid.
      cognitoUser.getSession((sessionErr: Error | null, session: CognitoUserSession | null) => {
        if (sessionErr || !session?.isValid()) {
          observer.error(sessionErr ?? new Error('Session expired. Please log in again.'));
          return;
        }

        cognitoUser.changePassword(currentPassword, newPassword, (changeErr, _result) => {
          if (changeErr) {
            observer.error(changeErr);
          } else {
            observer.next();
            observer.complete();
          }
        });
      });
    });
  }

  // ── Logout ────────────────────────────────────────────────────────────────

  /**
   * Signs the user out globally (invalidates the refresh token in Cognito)
   * and wipes all Cognito keys from sessionStorage.
   */
  logout(): void {
    const cognitoUser = this.userPool.getCurrentUser();
    if (cognitoUser) {
      cognitoUser.signOut();   // clears sessionStorage keys set by the SDK
    }
    sessionStorage.removeItem('userEmployeeType');
    sessionStorage.removeItem('userDisplayName');
    this.pendingChallengeUser = null;
  }

  // ── Session checks ────────────────────────────────────────────────────────

  /**
   * Returns an Observable<boolean> — true if the user has a valid, non-expired
   * session (the SDK auto-refreshes the tokens using the refresh token if needed).
   *
   * Used by AuthGuard to decide whether to allow or redirect a navigation.
   */
  isAuthenticated(): Observable<boolean> {
    return new Observable(observer => {
      const cognitoUser = this.userPool.getCurrentUser();

      if (!cognitoUser) {
        observer.next(false);
        observer.complete();
        return;
      }

      cognitoUser.getSession((err: Error | null, session: CognitoUserSession | null) => {
        observer.next(!err && session !== null && session.isValid());
        observer.complete();
      });
    });
  }

  /**
   * True when a NEW_PASSWORD_REQUIRED challenge is in progress.
   * Used by ChallengeGuard to gate the /auth/new-password route.
   */
  hasPendingChallenge(): boolean {
    return this.pendingChallengeUser !== null;
  }

  // ── Token helpers ─────────────────────────────────────────────────────────

  /**
   * Returns the raw ID token JWT string synchronously from sessionStorage.
   *
   * The ID token is what the backend Lambda reads — it contains:
   *   sub             → userId (matches DynamoDB partition key)
   *   cognito:groups  → ['ADMIN'] or ['USER']
   *
   * Returns null if the user is not authenticated.
   */
  getIdToken(): string | null {
    const clientId  = environment.cognito.clientId;
    const lastUser  = sessionStorage.getItem(
      `CognitoIdentityServiceProvider.${clientId}.LastAuthUser`
    );
    if (!lastUser) return null;
    return sessionStorage.getItem(
      `CognitoIdentityServiceProvider.${clientId}.${lastUser}.idToken`
    );
  }

  /**
   * Decodes the ID token payload (base64url) and returns the claims.
   * No signature verification — API Gateway already verified it.
   * Returns null if the user is not authenticated.
   */
  getTokenPayload(): CognitoTokenPayload | null {
    const token = this.getIdToken();
    if (!token) return null;
    try {
      const payload = token.split('.')[1];
      const decoded = atob(payload.replace(/-/g, '+').replace(/_/g, '/'));
      return JSON.parse(decoded) as CognitoTokenPayload;
    } catch {
      return null;
    }
  }

  // ── Password same-as-current check ───────────────────────────────────────

  /**
   * Returns true if `password` is the user's current Cognito password.
   * Works by attempting a silent authentication — if it succeeds, the
   * password matches the current one and we immediately sign out.
   * Used by the forgot-password flow to block same-password resets.
   */
  isSameAsCurrentPassword(email: string, password: string): Observable<boolean> {
    return new Observable(observer => {
      const authDetails = new AuthenticationDetails({ Username: email, Password: password });
      const cognitoUser = new CognitoUser({
        Username: email,
        Pool:     this.userPool,
        Storage:  window.sessionStorage,
      });

      cognitoUser.authenticateUser(authDetails, {
        onSuccess: () => {
          cognitoUser.signOut();   // don't actually establish a session
          observer.next(true);
          observer.complete();
        },
        onFailure: (err: Error) => {
          if (err.name === 'NotAuthorizedException') {
            // Wrong password — new password differs from current
            observer.next(false);
            observer.complete();
          } else {
            observer.error(err);
          }
        },
        newPasswordRequired: () => {
          // User has a temp/forced-change password — allow the reset
          observer.next(false);
          observer.complete();
        },
      });
    });
  }

  // ── Forgot Password ───────────────────────────────────────────────────────

  /**
   * Triggers Cognito's forgot-password flow — sends a verification code
   * to the user's registered email address.
   * Resolves when the code has been dispatched.
   */
  forgotPassword(email: string): Observable<void> {
    return new Observable(observer => {
      const cognitoUser = new CognitoUser({
        Username: email,
        Pool:     this.userPool,
        Storage:  window.sessionStorage,
      });

      cognitoUser.forgotPassword({
        onSuccess: () => {
          observer.next();
          observer.complete();
        },
        onFailure: (err: Error) => {
          observer.error(err);
        },
        inputVerificationCode: () => {
          observer.next();
          observer.complete();
        },
      });
    });
  }

  /**
   * Confirms the forgot-password flow by submitting the verification code
   * and the new password. On success the password is updated in Cognito.
   */
  confirmForgotPassword(email: string, code: string, newPassword: string): Observable<void> {
    return new Observable(observer => {
      const cognitoUser = new CognitoUser({
        Username: email,
        Pool:     this.userPool,
        Storage:  window.sessionStorage,
      });

      cognitoUser.confirmPassword(code, newPassword, {
        onSuccess: () => {
          observer.next();
          observer.complete();
        },
        onFailure: (err: Error) => {
          observer.error(err);
        },
      });
    });
  }

  /** Convenience helper — true if the user belongs to the ADMIN Cognito group. */
  isAdmin(): boolean {
    const payload = this.getTokenPayload();
    return payload?.['cognito:groups']?.includes('ADMIN') ?? false;
  }

  /** Returns the current user's email, or null if not authenticated. */
  getUserEmail(): string | null {
    return this.getTokenPayload()?.email ?? null;
  }

  // ── Role caching (from backend employeeType) ──────────────────────────────

  /** Cache the user's role and display name from the backend after login. */
  cacheUserRole(role: string): void {
    sessionStorage.setItem('userEmployeeType', role);
  }

  cacheUserName(firstName: string, lastName: string): void {
    sessionStorage.setItem('userDisplayName', `${firstName} ${lastName}`.trim());
  }

  /** Returns the cached role (ADMIN, MANAGER, EMPLOYEE). */
  getUserEmployeeType(): string | null {
    return sessionStorage.getItem('userEmployeeType');
  }

  /** Returns cached full name, falls back to email prefix. */
  getUserDisplayName(): string | null {
    return sessionStorage.getItem('userDisplayName');
  }

  /** True if the user is a Manager or Admin. */
  isManagerOrAdmin(): boolean {
    const role = this.getUserEmployeeType();
    // isAdmin() uses Cognito groups (works even without cached role)
    if (this.isAdmin()) return true;
    // If role not cached yet, deny manager access until re-login
    return role === 'MANAGER' || role === 'ADMIN';
  }
}
