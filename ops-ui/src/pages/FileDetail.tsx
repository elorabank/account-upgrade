import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { query } from '../api';
import { useFetch } from '../hooks';
import { Empty, Notice, Pager, Status, when } from '../components';
import type { FileError, FileView, Page } from '../types';

export default function FileDetail() {
  const { id } = useParams();
  const [page, setPage] = useState(0);
  const file = useFetch<FileView>(`/api/files/${id}`);
  const errors = useFetch<Page<FileError>>(`/api/files/${id}/errors${query({ page, size: 50 })}`);
  const f = file.data;

  return (
    <>
      <p><Link to="/files">← All files</Link></p>
      {file.error && <Notice>{file.error}</Notice>}
      {f && (
        <>
          <h1>{f.fileName} <Status value={f.status} /></h1>
          <dl className="facts">
            <dt>Business date</dt><dd>{f.businessDate}</dd>
            <dt>Rows</dt><dd>{f.totalRows?.toLocaleString() ?? '–'} ({f.loadedRows?.toLocaleString() ?? 0} loaded, {f.rejectedRows?.toLocaleString() ?? 0} rejected)</dd>
            <dt>Started</dt><dd>{when(f.startedAt)}</dd>
            <dt>Finished</dt><dd>{when(f.completedAt) || '–'}</dd>
            {f.errorMessage && <><dt>Problem</dt><dd className="bad-text">{f.errorMessage}</dd></>}
          </dl>
          <p><Link to={`/upgrades?fileId=${f.id}`}>Upgrades from this file →</Link></p>
        </>
      )}
      <h2>Rejected rows</h2>
      {errors.error && <Notice>{errors.error}</Notice>}
      {errors.data && errors.data.items.length > 0 ? (
        <table>
          <thead><tr><th className="num">Row</th><th>Why</th><th>Row as in the file</th></tr></thead>
          <tbody>
            {errors.data.items.map(e => (
              <tr key={e.rowNo}>
                <td className="num">{e.rowNo}</td>
                <td className="wrap">{e.errorMessage}</td>
                <td className="mono wrap">{e.rawRow}</td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : (
        <Empty loading={errors.loading}>No rows were rejected.</Empty>
      )}
      <Pager page={errors.data} onPage={setPage} />
    </>
  );
}
