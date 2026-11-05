import { useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { post, query } from '../api';
import { useAccess, useFetch } from '../hooks';
import { Empty, Notice, Pager, Status, when } from '../components';
import type { LetterView, Page } from '../types';

export default function Emails() {
  const { resend } = useAccess();
  const [params, setParams] = useSearchParams();
  const [message, setMessage] = useState<{ kind: 'info' | 'error'; text: string } | null>(null);
  const status = params.get('status') ?? '';
  const accountNo = params.get('accountNo') ?? '';
  const page = Number(params.get('page') ?? 0);
  const { data, error, loading, reload } = useFetch<Page<LetterView>>(`/api/notifications${query({ status, accountNo, page, size: 50 })}`);

  const set = (key: string, value: string) => {
    const next = new URLSearchParams(params);
    if (value) next.set(key, value); else next.delete(key);
    if (key !== 'page') next.delete('page');
    setParams(next);
  };

  const sendAgain = (l: LetterView) => {
    setMessage(null);
    post(`/api/notifications/${l.id}/resend`)
      .then(() => { setMessage({ kind: 'info', text: `${l.accountNo}: queued to be sent again` }); reload(); })
      .catch(e => setMessage({ kind: 'error', text: e.message }));
  };

  return (
    <>
      <h1>Welcome emails</h1>
      <div className="filters">
        <label>Status
          <select value={status} onChange={e => set('status', e.target.value)}>
            <option value="">all</option>
            <option>PENDING</option>
            <option>SENT</option>
            <option>FAILED</option>
          </select>
        </label>
        <label>Account
          <input defaultValue={accountNo} placeholder="account number"
            onKeyDown={e => e.key === 'Enter' && set('accountNo', (e.target as HTMLInputElement).value.trim())} />
        </label>
      </div>
      {error && <Notice>{error}</Notice>}
      {message && <Notice kind={message.kind}>{message.text}</Notice>}
      {data && data.items.length > 0 ? (
        <table>
          <thead>
            <tr><th>Account</th><th>To</th><th>Subject</th><th>Status</th><th className="num">Attempts</th><th>Sent</th><th>Problem</th>{resend && <th />}</tr>
          </thead>
          <tbody>
            {data.items.map(l => (
              <tr key={l.id}>
                <td className="mono">{l.accountNo}</td>
                <td>{l.recipient}</td>
                <td>{l.subject}</td>
                <td><Status value={l.status} /></td>
                <td className="num">{l.attempts}</td>
                <td>{when(l.sentAt) || (l.nextAttemptAt ? `next try ${when(l.nextAttemptAt)}` : '')}</td>
                <td className="wrap">{l.errorMessage}</td>
                {resend && <td><button onClick={() => sendAgain(l)}>Resend</button></td>}
              </tr>
            ))}
          </tbody>
        </table>
      ) : (
        <Empty loading={loading}>No emails match.</Empty>
      )}
      <Pager page={data} onPage={p => set('page', String(p))} />
    </>
  );
}
