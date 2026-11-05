import { useState } from 'react';
import { Link } from 'react-router-dom';
import { post } from '../api';
import { useAccess, useFetch } from '../hooks';
import { Notice, Status, when } from '../components';
import type { FileView, JobStart, LetterSummary, Page, UpgradeSummary } from '../types';

function Tile({ label, value, tone, to }: { label: string; value: number | string; tone?: string; to?: string }) {
  const body = (
    <>
      <span className="tile-value">{typeof value === 'number' ? value.toLocaleString() : value}</span>
      <span className="tile-label">{label}</span>
    </>
  );
  return to ? <Link className={`tile ${tone ?? ''}`} to={to}>{body}</Link> : <div className={`tile ${tone ?? ''}`}>{body}</div>;
}

function RunButton({ job, label }: { job: 'ingestion' | 'upgrade'; label: string }) {
  const [state, setState] = useState<{ busy: boolean; result?: JobStart; error?: string }>({ busy: false });
  const run = () => {
    setState({ busy: true });
    post<JobStart>(`/api/jobs/${job}/run`)
      .then(result => setState({ busy: false, result }))
      .catch(e => setState({ busy: false, error: e.message }));
  };
  return (
    <div className="run">
      <button onClick={run} disabled={state.busy}>{state.busy ? 'Starting…' : label}</button>
      {state.result && <span className={state.result.started ? 'ok' : 'warn'}>{state.result.message}</span>}
      {state.error && <span className="warn">{state.error}</span>}
    </div>
  );
}

export default function Dashboard() {
  const { admin, readEmails } = useAccess();
  const latestFile = useFetch<Page<FileView>>('/api/files?size=1');
  const upgrades = useFetch<UpgradeSummary>('/api/upgrades/summary');
  // Fixed when the page opens: a new value on every render would change the URL and fetch again, endlessly
  const [since] = useState(() => new Date(Date.now() - 24 * 3600 * 1000).toISOString());
  const emails = useFetch<LetterSummary>(readEmails ? `/api/notifications/summary?since=${encodeURIComponent(since)}` : null);
  const file = latestFile.data?.items[0];
  const u = upgrades.data;
  const date = u?.businessDate;

  return (
    <>
      <h1>Today</h1>
      {[latestFile.error, upgrades.error, emails.error].filter(Boolean).map(e => <Notice key={e}>{e}</Notice>)}

      <section>
        <h2>Latest file</h2>
        {file ? (
          <div className="file-line">
            <Link to={`/files/${file.id}`}>{file.fileName}</Link> <Status value={file.status} />
            <span>business date {file.businessDate}</span>
            <span>{(file.loadedRows ?? 0).toLocaleString()} loaded, {(file.rejectedRows ?? 0).toLocaleString()} rejected</span>
            <span className="muted">{when(file.completedAt ?? file.startedAt)}</span>
          </div>
        ) : (
          <p className="muted">{latestFile.loading ? 'Loading…' : 'No file has been loaded yet.'}</p>
        )}
      </section>

      <section>
        <h2>Upgrades {date && <span className="muted">for {date}</span>}</h2>
        {u && (
          <div className="tiles">
            <Tile label="upgraded" value={u.byStatus.UPGRADED} tone="good" to={`/upgrades?status=UPGRADED&businessDate=${date}`} />
            <Tile label="waiting" value={u.byStatus.PENDING + u.byStatus.IN_PROGRESS} tone="busy" to={`/upgrades?status=PENDING&businessDate=${date}`} />
            <Tile label="not eligible" value={u.byStatus.REJECTED} to={`/upgrades?status=REJECTED&businessDate=${date}`} />
            <Tile label="failed" value={u.byStatus.FAILED} tone={u.byStatus.FAILED ? 'bad' : ''} to={`/upgrades?status=FAILED&businessDate=${date}`} />
          </div>
        )}
      </section>

      {readEmails && (
        <section>
          <h2>Welcome emails <span className="muted">last 24 hours</span></h2>
          {emails.data && (
            <div className="tiles">
              <Tile label="sent" value={emails.data.byStatus.SENT} tone="good" to="/emails?status=SENT" />
              <Tile label="waiting" value={emails.data.byStatus.PENDING} tone="busy" to="/emails?status=PENDING" />
              <Tile label="failed" value={emails.data.byStatus.FAILED} tone={emails.data.byStatus.FAILED ? 'bad' : ''} to="/emails?status=FAILED" />
            </div>
          )}
        </section>
      )}

      {admin && (
        <section>
          <h2>Run now</h2>
          <p className="muted">Starts the job straight away instead of at its scheduled time. Only one run of each job can go at a time.</p>
          <RunButton job="ingestion" label="Load files from SFTP" />
          <RunButton job="upgrade" label="Run the upgrade" />
        </section>
      )}
    </>
  );
}
