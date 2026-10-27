import { Component } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';

import { LoadingSpinnerComponent } from './loading-spinner.component';

@Component({
  standalone: true,
  imports: [LoadingSpinnerComponent],
  template: '<app-loading-spinner [message]="message" [fullPage]="fullPage"></app-loading-spinner>',
})
class HostComponent {
  message = 'Loading reviews...';
  fullPage = false;
}

describe('LoadingSpinnerComponent', () => {
  let fixture: ComponentFixture<HostComponent>;

  // Keeps DOM queries readable and avoids repeating nativeElement plumbing.
  function query<T extends Element>(selector: string): T | null {
    return fixture.nativeElement.querySelector(selector);
  }

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [HostComponent],
    });

    fixture = TestBed.createComponent(HostComponent);
    fixture.detectChanges();
  });

  it('renders the spinner and supplied message', () => {
    expect(query('.spinner')).toBeTruthy();
    expect(query<HTMLElement>('.spinner-message')?.textContent?.trim()).toBe('Loading reviews...');
  });

  it('adds full-page class when requested', () => {
    fixture.componentInstance.fullPage = true;
    fixture.detectChanges();

    expect(query('.spinner-wrapper')?.classList.contains('full-page')).toBeTrue();
  });

  it('hides the message element when message is empty', () => {
    fixture.componentInstance.message = '';
    fixture.detectChanges();

    // The template uses *ngIf, so no empty paragraph should remain in the DOM.
    expect(query('.spinner-message')).toBeNull();
  });
});
