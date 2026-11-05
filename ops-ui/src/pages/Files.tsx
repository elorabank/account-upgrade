import { useState } from 'react';
import { Link } from 'react-router-dom';
import { query } from '../api';
import { useFetch } from '../hooks';
import { Empty, Notice, Pager, Status, when } from '../components';
import type { FileView, Page } from '../types';

export default function Files() {
  const [status, setStatus] = useState('');
  const [page, setPage] = useState(0);
  const { data, error, loading } = useFetch<Page<FileView>>(`/api/files${query({ status, page, size: 20 })}`);

  return (
    <>
      <h1>Files</h1>
      <div className="filters">
        <label>Status
          <select value={status} onChange={e => { setStatus(e.target.value); setPage(0); }}>
            <option value="">all</option>
            <option>COMPLETED</option>
            <option>RUNNING</option>
            <option>FAILED</option>
          </select>
        </label>
      </div>
      {error && <Notice>{error}</Notice>}
      {data && data.items.length > 0 ? (
        <table>
          <thead>
            <tr><th>File</th><th>Business date</th><th>Status</th><th className="num">Loaded</th><th className="num">Rejected</th><th>Finished</th><th>Problem</th></tr>
          </thead>
          <tbody>
            {data.items.map(f => (
              <tr key={f.id}>
                <td><Link to={`/files/${f.id}`}>{f.fileName}</Link></td>
                <td>{f.businessDate}</td>
                <td><Status value={f.status} /></td>
                <td className="num">{f.loadedRows?.toLocaleString() ?? ''}</td>
                <td className="num">{f.rejectedRows?.toLocaleString() ?? ''}</td>
                <td>{when(f.completedAt)}</td>
                <td className="wrap">{f.errorMessage}</td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : (
        <Empty loading={loading}>No files.</Empty>
      )}
      <Pager page={data} onPage={setPage} />
    </>
  );
}
