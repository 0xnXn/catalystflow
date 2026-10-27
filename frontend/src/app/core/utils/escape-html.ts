/**
 * Escapes a value for safe interpolation into an HTML string.
 *
 * Used by the PDF/print export functions, which assemble an HTML document by
 * string concatenation and write it into an iframe via `document.write()` —
 * a path that bypasses Angular's template auto-escaping. Every untrusted DATA
 * value (review answers, question text, names, titles, AI narrative fields)
 * MUST be wrapped in this before interpolation, or it becomes a DOM-XSS sink.
 *
 * `&` is escaped first so the entity ampersands introduced by the later
 * replacements are not double-escaped. null/undefined collapse to '' so the
 * document never shows the literal text "null"/"undefined".
 */
export function escapeHtml(value: unknown): string {
  if (value === null || value === undefined) return '';
  return String(value)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}
