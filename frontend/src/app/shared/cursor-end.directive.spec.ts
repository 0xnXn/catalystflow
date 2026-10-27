import { Component } from '@angular/core';
import { ComponentFixture, TestBed, fakeAsync, tick } from '@angular/core/testing';

import { CursorEndDirective } from './cursor-end.directive';

@Component({
  standalone: true,
  imports: [CursorEndDirective],
  template: '<input cursorEnd [value]="value" />',
})
class HostComponent {
  value = 'CatalystFlow';
}

describe('CursorEndDirective', () => {
  let fixture: ComponentFixture<HostComponent>;
  let input: HTMLInputElement;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [HostComponent],
    });

    fixture = TestBed.createComponent(HostComponent);
    fixture.detectChanges();
    input = fixture.nativeElement.querySelector('input');
  });

  it('creates the host input with the directive applied', () => {
    expect(input).toBeTruthy();
    expect(input.value).toBe('CatalystFlow');
  });

  it('moves the cursor to the end after focus', fakeAsync(() => {
    input.setSelectionRange(0, 0);

    input.dispatchEvent(new FocusEvent('focus'));
    tick();

    expect(input.selectionStart).toBe(input.value.length);
    expect(input.selectionEnd).toBe(input.value.length);
  }));

  it('uses the current value length when focus occurs', fakeAsync(() => {
    fixture.componentInstance.value = 'Updated value';
    fixture.detectChanges();
    input.setSelectionRange(0, 0);

    input.dispatchEvent(new FocusEvent('focus'));
    tick();

    expect(input.selectionStart).toBe('Updated value'.length);
    expect(input.selectionEnd).toBe('Updated value'.length);
  }));
});
