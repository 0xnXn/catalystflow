/**
 * Shared password-strength utilities used by NewPasswordComponent
 * and PasswordManagementComponent.
 *
 * Cognito's default policy requires all five checks to pass.
 */

export interface PasswordChecks {
  minLength:  boolean;
  hasUpper:   boolean;
  hasLower:   boolean;
  hasNumber:  boolean;
  hasSpecial: boolean;
}

export interface PasswordStrength {
  checks:        PasswordChecks;
  label:         string;  // 'Weak' | 'Fair' | 'Good' | 'Strong'
  cssClass:      string;  // 'weak' | 'fair' | 'good' | 'strong'
  widthPercent:  string;  // '20%' | '50%' | '75%' | '100%'
}

const SPECIAL_CHARS = /[!@#$%^&*()_+\-=\[\]{};':"\\|,.<>\/?]/;

/** Returns strength info for the given password string. */
export function evaluatePasswordStrength(password: string): PasswordStrength {
  const checks: PasswordChecks = {
    minLength:  password.length >= 8,
    hasUpper:   /[A-Z]/.test(password),
    hasLower:   /[a-z]/.test(password),
    hasNumber:  /[0-9]/.test(password),
    hasSpecial: SPECIAL_CHARS.test(password),
  };

  const score = Object.values(checks).filter(Boolean).length;

  if (score <= 1) return { checks, label: 'Weak',   cssClass: 'weak',   widthPercent: '20%'  };
  if (score === 2) return { checks, label: 'Fair',   cssClass: 'fair',   widthPercent: '50%'  };
  if (score <= 4)  return { checks, label: 'Good',   cssClass: 'good',   widthPercent: '75%'  };
                   return { checks, label: 'Strong', cssClass: 'strong', widthPercent: '100%' };
}
