import type { ReactNode } from 'react';
import type { Page } from './types';

export function when(iso: string | null | undefined): string {
  return iso ? new Date(iso).toLocaleString() : '';
}

const TONE: Record<string, string> = {
  COMPLETED: 'good', UPGRADED: 'good', SENT: 'good',
  RUNNING: 'busy', PENDING: 'busy', IN_PROGRESS: 'busy',
  REJECTED: 'muted',
  FAILED: 'bad',
};

export function Status({ value }: { value: string }) {
  return <span className={`status ${TONE[value] ?? 'muted'}`}>{value.replace('_', ' ').toLowerCase()}</span>;
}

export function Notice({ kind = 'error', children }: { kind?: 'error' | 'info'; children: ReactNode }) {
  return <div className={`notice ${kind}`} role={kind === 'error' ? 'alert' : 'status'}>{children}</div>;
}

export function Pager<T>({ page, onPage }: { page: Page<T> | null; onPage: (p: number) => void }) {
  if (!page || page.total <= page.size) return null;
  const last = Math.ceil(page.total / page.size) - 1;
  return (
    <div className="pager">
      <button disabled={page.page === 0} onClick={() => onPage(page.page - 1)}>Previous</button>
      <span>Page {page.page + 1} of {last + 1} · {page.total.toLocaleString()} in all</span>
      <button disabled={page.page >= last} onClick={() => onPage(page.page + 1)}>Next</button>
    </div>
  );
}

export function Empty({ loading, children }: { loading: boolean; children: ReactNode }) {
  return <p className="empty">{loading ? 'Loading…' : children}</p>;
}
