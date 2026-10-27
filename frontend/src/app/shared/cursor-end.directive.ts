import { Directive, HostListener } from '@angular/core';

@Directive({ selector: 'input[cursorEnd]', standalone: true })
export class CursorEndDirective {
  @HostListener('focus', ['$event.target'])
  onFocus(el: HTMLInputElement) {
    const len = el.value.length;
    setTimeout(() => el.setSelectionRange(len, len));
  }
}
