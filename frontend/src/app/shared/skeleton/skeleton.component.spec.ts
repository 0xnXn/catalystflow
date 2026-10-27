import { Component } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';

import { SkeletonComponent } from './skeleton.component';

@Component({
  standalone: true,
  imports: [SkeletonComponent],
  template: '<app-skeleton [width]="width" [height]="height" [radius]="radius"></app-skeleton>',
})
class HostComponent {
  width = '60%';
  height = '24px';
  radius = '12px';
}

describe('SkeletonComponent', () => {
  let fixture: ComponentFixture<HostComponent>;

  // The rendered placeholder is the public surface: one span with bound styles.
  function skeleton(): HTMLElement {
    return fixture.nativeElement.querySelector('.sk');
  }

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [HostComponent],
    });

    fixture = TestBed.createComponent(HostComponent);
    fixture.detectChanges();
  });

  it('renders a placeholder span', () => {
    expect(skeleton()).toBeTruthy();
    expect(skeleton().tagName.toLowerCase()).toBe('span');
  });

  it('applies configured dimensions and radius', () => {
    const el = skeleton();

    expect(el.style.width).toBe('60%');
    expect(el.style.height).toBe('24px');
    expect(el.style.borderRadius).toBe('12px');
  });

  it('updates inline styles when inputs change', () => {
    fixture.componentInstance.width = '100px';
    fixture.componentInstance.height = '8px';
    fixture.componentInstance.radius = '999px';
    fixture.detectChanges();

    expect(skeleton().style.width).toBe('100px');
    expect(skeleton().style.height).toBe('8px');
    expect(skeleton().style.borderRadius).toBe('999px');
  });

  it('uses default input values when rendered directly', () => {
    const direct = TestBed.createComponent(SkeletonComponent);
    direct.detectChanges();
    const el: HTMLElement = direct.nativeElement.querySelector('.sk');

    // Defaults are useful for quick line placeholders with no input ceremony.
    expect(el.style.width).toBe('100%');
    expect(el.style.height).toBe('14px');
    expect(el.style.borderRadius).toBe('6px');
  });
});
