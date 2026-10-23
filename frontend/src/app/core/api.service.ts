import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

type Params = Record<string, string | number | boolean | null | undefined>;

/** Thin wrapper over HttpClient for the ICCP REST API (/api/v1). */
@Injectable({ providedIn: 'root' })
export class Api {
  private http = inject(HttpClient);
  readonly base = '/api/v1';

  get<T>(path: string, params?: Params): Observable<T> {
    return this.http.get<T>(this.base + path, { params: toParams(params) });
  }

  post<T>(path: string, body: unknown = {}, params?: Params): Observable<T> {
    return this.http.post<T>(this.base + path, body, { params: toParams(params) });
  }

  put<T>(path: string, body: unknown): Observable<T> {
    return this.http.put<T>(this.base + path, body);
  }

  patch<T>(path: string, body: unknown): Observable<T> {
    return this.http.patch<T>(this.base + path, body);
  }

  delete<T>(path: string): Observable<T> {
    return this.http.delete<T>(this.base + path);
  }

  upload<T>(path: string, file: File, field = 'file'): Observable<T> {
    const form = new FormData();
    form.append(field, file, file.name);
    return this.http.post<T>(this.base + path, form);
  }

  /** Downloads a protected file with the bearer token and opens the browser's save flow. */
  download(path: string, fileName: string): void {
    this.http.get(this.base + path, { responseType: 'blob' }).subscribe((blob) => {
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = fileName;
      a.click();
      setTimeout(() => URL.revokeObjectURL(url), 2000);
    });
  }
}

function toParams(params?: Params): HttpParams | undefined {
  if (!params) return undefined;
  let p = new HttpParams();
  for (const [k, v] of Object.entries(params)) {
    if (v !== null && v !== undefined && v !== '') p = p.set(k, String(v));
  }
  return p;
}
