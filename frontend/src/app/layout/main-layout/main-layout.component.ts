import { Component, ViewChild } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterOutlet } from '@angular/router';
import { SidebarComponent } from '../sidebar/sidebar.component';

@Component({
  selector: 'app-main-layout',
  standalone: true,
  imports: [CommonModule, RouterOutlet, SidebarComponent],
  templateUrl: './main-layout.component.html',
  styleUrls: ['./main-layout.component.css']
})
export class MainLayoutComponent {
  @ViewChild('sidebarRef') sidebarRef!: SidebarComponent;

  isSidebarVisible = false;
  isSidebarCollapsed = false;

  toggleSidebar() { this.isSidebarVisible = !this.isSidebarVisible; }
  closeSidebar()  { this.isSidebarVisible = false; }
  onSidebarCollapsed(collapsed: boolean) { this.isSidebarCollapsed = collapsed; }
}
