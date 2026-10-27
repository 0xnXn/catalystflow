import { AfterViewInit, Directive, ElementRef, HostListener, Input, OnChanges, inject } from '@angular/core';

/**
 * Grows a <textarea> to fit its content (industry-standard auto-expand), up to
 * {@link maxRows} rows after which it scrolls internally. Self-contained: it
 * sets its own `resize`/`overflow` inline so shared `.q-input` styling on other
 * forms is left alone.
 *
 * Bind the model value to `[autosize]` so the box also re-measures when content
 * is set programmatically (e.g. loading a saved draft), not only on keystroke.
 */
@Directive({ selector: 'textarea[autosize]', standalone: true })
export class AutosizeDirective implements AfterViewInit, OnChanges {
  private el = inject(ElementRef<HTMLTextAreaElement>);

  /** Bound model value — its changes trigger a re-measure. */
  @Input('autosize') value: unknown;

  /** Rows to grow to before the textarea starts scrolling. */
  @Input() maxRows = 15;

  ngAfterViewInit(): void {
    this.el.nativeElement.style.resize = 'none';
    // Defer so ngModel has written the initial value into the DOM.
    setTimeout(() => this.resize());
  }

  ngOnChanges(): void {
    setTimeout(() => this.resize());
  }

  @HostListener('input')
  onInput(): void {
    this.resize();
  }

  private resize(): void {
    const ta = this.el.nativeElement;
    if (!ta) return;

    const style = getComputedStyle(ta);
    const lineHeight = parseFloat(style.lineHeight) || 20;
    const paddingV = parseFloat(style.paddingTop) + parseFloat(style.paddingBottom);
    const borderV = parseFloat(style.borderTopWidth) + parseFloat(style.borderBottomWidth);
    const minHeight = parseFloat(style.minHeight) || 0;
    const maxHeight = lineHeight * this.maxRows + paddingV + borderV;

    // Reset so scrollHeight reflects the true content height, then clamp.
    ta.style.height = 'auto';
    const next = Math.min(Math.max(ta.scrollHeight, minHeight), maxHeight);
    ta.style.height = `${next}px`;
    ta.style.overflowY = ta.scrollHeight > maxHeight ? 'auto' : 'hidden';
  }
}
