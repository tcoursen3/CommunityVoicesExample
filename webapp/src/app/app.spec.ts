import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import { App } from './app';

describe('App', () => {
  let httpTestingController: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [provideHttpClient(), provideHttpClientTesting()]
    }).compileComponents();

    httpTestingController = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpTestingController.verify();
  });

  it('should create the app', () => {
    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();
    httpTestingController.expectOne('/generate-report').flush('<html><body>Report</body></html>');
    const app = fixture.componentInstance;
    expect(app).toBeTruthy();
  });

  it('should render a refresh toolbar button', async () => {
    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();
    httpTestingController.expectOne('/generate-report').flush('<html><body>Report</body></html>');
    await fixture.whenStable();
    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('.toolbar-button')?.getAttribute('aria-label')).toBe('Refresh report');
  });

  it('should render the report html in an iframe', async () => {
    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();
    const request = httpTestingController.expectOne('/generate-report');
    request.flush('<html><body><h1>Community Voices Document</h1></body></html>');

    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    const app = fixture.componentInstance;
    const compiled = fixture.nativeElement as HTMLElement;
    const iframe = compiled.querySelector('iframe') as HTMLIFrameElement | null;
    const statusPanel = compiled.querySelector('.status-panel');

    expect(app.reportHtml).toContain('Community Voices Document');
    expect(app.isLoading).toBe(false);
    expect(app.errorMessage).toBe('');
    expect(statusPanel).toBeNull();
    expect(iframe).not.toBeNull();
    expect(iframe?.srcdoc).toContain('Community Voices Document');
  });

  it('should show an error when the angular app shell is returned instead of report html', async () => {
    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();
    const request = httpTestingController.expectOne('/generate-report');
    request.flush('<!doctype html><html><body><app-root></app-root><script type="module" src="/main.ts"></script></body></html>');

    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    const app = fixture.componentInstance;
    const compiled = fixture.nativeElement as HTMLElement;
    const statusPanel = compiled.querySelector('.status-panel');
    const iframe = compiled.querySelector('iframe');

    expect(app.reportHtml).toBe('');
    expect(app.isLoading).toBe(false);
    expect(app.errorMessage).toContain('Angular app');
    expect(statusPanel?.textContent).toContain('Angular app');
    expect(iframe).toBeNull();
  });
});
