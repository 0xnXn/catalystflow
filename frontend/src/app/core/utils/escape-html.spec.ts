import { escapeHtml } from './escape-html';

describe('escapeHtml', () => {
  it('escapes the five HTML-significant characters', () => {
    expect(escapeHtml('<')).toBe('&lt;');
    expect(escapeHtml('>')).toBe('&gt;');
    expect(escapeHtml('"')).toBe('&quot;');
    expect(escapeHtml("'")).toBe('&#39;');
    expect(escapeHtml('&')).toBe('&amp;');
  });

  it('neutralises a script-injection payload', () => {
    const payload = '<img src=x onerror="alert(1)">';
    const out = escapeHtml(payload);
    expect(out).toBe('&lt;img src=x onerror=&quot;alert(1)&quot;&gt;');
    expect(out).not.toContain('<img');
    expect(out).not.toContain('onerror="');
  });

  it('escapes & before the other replacements (no double-escaping)', () => {
    // A literal "<" must become "&lt;" — not "&amp;lt;".
    expect(escapeHtml('a < b & c')).toBe('a &lt; b &amp; c');
    expect(escapeHtml('</script>')).toBe('&lt;/script&gt;');
  });

  it('maps null and undefined to an empty string', () => {
    expect(escapeHtml(null)).toBe('');
    expect(escapeHtml(undefined)).toBe('');
  });

  it('coerces non-strings without printing "null"/"undefined"', () => {
    expect(escapeHtml(42)).toBe('42');
    expect(escapeHtml(0)).toBe('0');
    expect(escapeHtml(false)).toBe('false');
  });

  it('leaves safe text unchanged', () => {
    expect(escapeHtml('Plain answer, nothing special.')).toBe('Plain answer, nothing special.');
  });
});
