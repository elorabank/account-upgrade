import { useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { post, query } from '../api';
import { useAccess, useFetch } from '../hooks';
import { Empty, Notice, Pager, Status, when } from '../components';
import type { Page, UpgradeView } from '../types';

export default function Upgrades() {
  const { admin } = useAccess();
  const [params, setParams] = useSearchParams();
  const [message, setMessage] = useState<{ kind: 'info' | 'error'; text: string } | null>(null);
  const filters = {
    status: params.get('status') ?? '',
    businessDate: params.get('businessDate') ?? '',
    accountNo: params.get('accountNo') ?? '',
    fileId: params.get('fileId') ?? '',
    page: Number(params.get('page') ?? 0),
  };
  const { data, error, loading, reload } = useFetch<Page<UpgradeView>>(`/api/upgrades${query({ ...filters, size: 50 })}`);

  const set = (key: string, value: string) => {
    const next = new URLSearchParams(params);
    if (value) next.set(key, value); else next.delete(key);
    if (key !== 'page') next.delete('page');
    setParams(next);
  };

  const retry = (u: UpgradeView) => {
    setMessage(null);
    post<{ message: string }>(`/api/upgrades/${u.id}/retry`)
      .then(r => { setMessage({ kind: 'info', text: `${u.accountNo}: ${r.message}` }); reload(); })
      .catch(e => setMessage({ kind: 'error', text: e.message }));
  };

  return (
    <>
      <h1>Upgrades</h1>
      <div className="filters">
        <label>Status
          <select value={filters.status} onChange={e => set('status', e.target.value)}>
            <option value="">all</option>
            {['PENDING', 'IN_PROGRESS', 'UPGRADED', 'REJECTED', 'FAILED'].map(s => <option key={s}>{s}</option>)}
          </select>
        </label>
        <label>Business date
          <input type="date" value={filters.businessDate} onChange={e => set('businessDate', e.target.value)} />
        </label>
        <label>Account
          <input defaultValue={filters.accountNo} placeholder="account number"
            onKeyDown={e => e.key === 'Enter' && set('accountNo', (e.target as HTMLInputElement).value.trim())} />
        </label>
        {filters.fileId && <span className="chip">file {filters.fileId} <button className="link" onClick={() => set('fileId', '')}>×</button></span>}
      </div>
      {error && <Notice>{error}</Notice>}
      {message && <Notice kind={message.kind}>{message.text}</Notice>}
      {data && data.items.length > 0 ? (
        <table>
          <thead>
            <tr><th>Account</th><th>Customer</th><th>From → to</th><th>Status</th><th className="num">Attempts</th><th>Reason</th><th>Date</th><th>Updated</th>{admin && <th />}</tr>
          </thead>
          <tbody>
            {data.items.map(u => (
              <tr key={u.id}>
                <td className="mono">{u.accountNo}</td>
                <td>{u.customerName}</td>
                <td>{u.currentType} → {u.targetType ?? '–'}</td>
                <td><Status value={u.status} /></td>
                <td className="num">{u.attemptCount}</td>
                <td className="wrap">{u.reason}</td>
                <td>{u.businessDate}</td>
                <td>{when(u.updatedAt)}</td>
                {admin && <td>{u.status === 'FAILED' && <button onClick={() => retry(u)}>Retry</button>}</td>}
              </tr>
            ))}
          </tbody>
        </table>
      ) : (
        <Empty loading={loading}>No upgrades match.</Empty>
      )}
      <Pager page={data} onPage={p => set('page', String(p))} />
    </>
  );
}
