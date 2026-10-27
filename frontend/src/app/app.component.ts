import { Component, inject } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { SwUpdateService } from './core/services/sw-update.service';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [RouterOutlet],
  templateUrl: './app.component.html',
  styleUrl: './app.component.css',
})
export class AppComponent {
  constructor() {
    inject(SwUpdateService).init();
  }
}
