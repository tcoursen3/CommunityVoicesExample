import { ChangeDetectorRef, Component, ElementRef, OnInit, ViewChild, inject } from '@angular/core';
import { ReportService } from './report.service';

@Component({
  selector: 'app-root',
  templateUrl: './app.html',
  styleUrl: './app.css'
})
export class App implements OnInit {
  private readonly changeDetectorRef = inject(ChangeDetectorRef);
  private readonly reportService = inject(ReportService);
  private reportFrameElement?: HTMLIFrameElement;

  reportHtml = '';
  errorMessage = '';
  isLoading = false;

  @ViewChild('reportFrame')
  set reportFrame(frame: ElementRef<HTMLIFrameElement> | undefined) {
    this.reportFrameElement = frame?.nativeElement;
    this.syncReportFrame();
  }

  ngOnInit(): void {
    this.loadReport();
  }

  loadReport(): void {
    this.isLoading = true;
    this.errorMessage = '';

    this.reportService.getReportHtml().subscribe({
      next: (reportHtml) => {
        if (this.isAppShellHtml(reportHtml)) {
          this.reportHtml = '';
          this.errorMessage = 'The report request resolved to the Angular app instead of the backend report endpoint.';
          this.isLoading = false;
          this.syncReportFrame();
          this.changeDetectorRef.detectChanges();
          return;
        }

        this.reportHtml = reportHtml;
        this.isLoading = false;
        this.syncReportFrame();
        this.changeDetectorRef.detectChanges();
      },
      error: () => {
        this.reportHtml = '';
        this.errorMessage = 'Unable to load the report.';
        this.isLoading = false;
        this.syncReportFrame();
        this.changeDetectorRef.detectChanges();
      }
    });
  }

  private syncReportFrame(): void {
    if (!this.reportFrameElement) {
      return;
    }

    this.reportFrameElement.srcdoc = this.reportHtml;
  }

  private isAppShellHtml(html: string): boolean {
    const normalizedHtml = html.toLowerCase();
    return normalizedHtml.includes('<app-root') || normalizedHtml.includes('<script type="module" src="/main.ts">');
  }
}
