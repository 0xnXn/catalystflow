import { evaluatePasswordStrength } from './password-strength';

describe('evaluatePasswordStrength', () => {
  it('should mark an empty password as weak with no checks passing', () => {
    // Empty input is the baseline state before the user starts typing.
    const result = evaluatePasswordStrength('');

    expect(result.checks).toEqual({
      minLength: false,
      hasUpper: false,
      hasLower: false,
      hasNumber: false,
      hasSpecial: false
    });
    expect(result.label).toBe('Weak');
    expect(result.cssClass).toBe('weak');
    expect(result.widthPercent).toBe('20%');
  });

  it('should return weak when only one rule passes', () => {
    // Score 1 still stays weak while the user has only met one requirement.
    const result = evaluatePasswordStrength('ABCDEFG');

    expect(result.checks.hasUpper).toBeTrue();
    expect(result.label).toBe('Weak');
  });

  it('should return fair when exactly two checks pass', () => {
    // This protects the score === 2 branch used by the strength bar.
    const result = evaluatePasswordStrength('abc123');

    expect(result.checks.hasLower).toBeTrue();
    expect(result.checks.hasNumber).toBeTrue();
    expect(result.label).toBe('Fair');
    expect(result.cssClass).toBe('fair');
    expect(result.widthPercent).toBe('50%');
  });

  it('should return good when three or four checks pass', () => {
    // Good covers partially-compliant passwords that are close but not Cognito-ready.
    const threeChecks = evaluatePasswordStrength('Abc123');
    const fourChecks = evaluatePasswordStrength('Abcdef12');

    expect(threeChecks.label).toBe('Good');
    expect(threeChecks.cssClass).toBe('good');
    expect(threeChecks.widthPercent).toBe('75%');
    expect(fourChecks.label).toBe('Good');
  });

  it('should return strong when all password policy checks pass', () => {
    // Strong is the only state where all five Cognito-style checks pass.
    const result = evaluatePasswordStrength('Abcdef12!');

    expect(result.checks).toEqual({
      minLength: true,
      hasUpper: true,
      hasLower: true,
      hasNumber: true,
      hasSpecial: true
    });
    expect(result.label).toBe('Strong');
    expect(result.cssClass).toBe('strong');
    expect(result.widthPercent).toBe('100%');
  });

  it('should count supported punctuation as special characters', () => {
    // The shared regex includes punctuation beyond the common !@#$ symbols.
    const result = evaluatePasswordStrength('Abcdef12]');

    expect(result.checks.hasSpecial).toBeTrue();
    expect(result.label).toBe('Strong');
  });
});
