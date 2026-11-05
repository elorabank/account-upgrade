import { useSearchParams } from 'react-router-dom';
import { query } from '../api';
import { useFetch } from '../hooks';
import { Empty, Notice, Pager, when } from '../components';
import type { AuditEventView, Page } from '../types';

const TYPES = ['FileLoaded', 'AccountUpgraded', 'UpgradeRejected', 'UpgradeFailed', 'NotificationSent', 'NotificationFailed', 'UserAction'];

export default function Audit() {
  const [params, setParams] = useSearchParams();
  const accountNo = params.get('accountNo') ?? '';
  const correlationId = params.get('correlationId') ?? '';
  const eventType = params.get('eventType') ?? '';
  const page = Number(params.get('page') ?? 0);
  const { data, error, loading } = useFetch<Page<AuditEventView>>(
    `/api/audit/events${query({ accountNo, correlationId, eventType, page, size: 50 })}`);

  const set = (key: string, value: string) => {
    const next = new URLSearchParams(params);
    if (value) next.set(key, value); else next.delete(key);
    if (key !== 'page') next.delete('page');
    setParams(next);
  };

  return (
    <>
      <h1>Audit trail</h1>
      <div className="filters">
        <label>Account
          <input defaultValue={accountNo} placeholder="account number"
            onKeyDown={e => e.key === 'Enter' && set('accountNo', (e.target as HTMLInputElement).value.trim())} />
        </label>
        <label>Business action
          <input defaultValue={correlationId} placeholder="correlation id"
            onKeyDown={e => e.key === 'Enter' && set('correlationId', (e.target as HTMLInputElement).value.trim())} />
        </label>
        <label>Event
          <select value={eventType} onChange={e => set('eventType', e.target.value)}>
            <option value="">all</option>
            {TYPES.map(t => <option key={t}>{t}</option>)}
          </select>
        </label>
      </div>
      <p className="muted">Newest first. Click a correlation id to follow that business action through every service.</p>
      {error && <Notice>{error}</Notice>}
      {data && data.items.length > 0 ? (
        <table>
          <thead><tr><th>When</th><th>Event</th><th>Account</th><th>What happened</th><th>Who</th><th>Action</th></tr></thead>
          <tbody>
            {data.items.map(e => (
              <tr key={e.eventId}>
                <td>{when(e.occurredAt)}</td>
                <td>{e.eventType}</td>
                <td className="mono">{e.accountNo && <button className="link" onClick={() => set('accountNo', e.accountNo!)}>{e.accountNo}</button>}</td>
                <td className="wrap">{e.summary}</td>
                <td>{e.actor}</td>
                <td className="mono">{e.correlationId && <button className="link" title={e.correlationId}
                  onClick={() => set('correlationId', e.correlationId!)}>{e.correlationId.substring(0, 8)}</button>}</td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : (
        <Empty loading={loading}>No audit events match.</Empty>
      )}
      <Pager page={data} onPage={p => set('page', String(p))} />
    </>
  );
}
