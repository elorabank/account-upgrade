import { useEffect, useState } from 'react';
import { NavLink, Navigate, Route, Routes } from 'react-router-dom';
import { get, signOut } from './api';
import { MeContext, useAccess } from './hooks';
import { Notice } from './components';
import type { Me } from './types';
import Dashboard from './pages/Dashboard';
import Files from './pages/Files';
import FileDetail from './pages/FileDetail';
import Upgrades from './pages/Upgrades';
import Emails from './pages/Emails';
import Audit from './pages/Audit';

function Shell() {
  const { me, readOps, readEmails, readAudit } = useAccess();
  if (!me) return null;
  return (
    <div className="shell">
      <header>
        <span className="brand">Account upgrade ops</span>
        <nav>
          {readOps && <NavLink to="/" end>Today</NavLink>}
          {readOps && <NavLink to="/files">Files</NavLink>}
          {readOps && <NavLink to="/upgrades">Upgrades</NavLink>}
          {readEmails && <NavLink to="/emails">Emails</NavLink>}
          {readAudit && <NavLink to="/audit">Audit</NavLink>}
        </nav>
        <span className="who" title={me.email ?? ''}>
          {me.name}
          <small>{me.roles.map(r => r.replace('AU_', '').replace('_', ' ').toLowerCase()).join(', ') || 'no role'}</small>
        </span>
        <button className="link" onClick={signOut}>Sign out</button>
      </header>
      <main>
        {me.roles.length === 0 ? (
          <Notice>You are signed in, but you are not in any of the account upgrade groups. Ask for access.</Notice>
        ) : (
          <Routes>
            <Route path="/" element={readOps ? <Dashboard /> : <Navigate to="/audit" />} />
            <Route path="/files" element={<Files />} />
            <Route path="/files/:id" element={<FileDetail />} />
            <Route path="/upgrades" element={<Upgrades />} />
            {readEmails && <Route path="/emails" element={<Emails />} />}
            {readAudit && <Route path="/audit" element={<Audit />} />}
            <Route path="*" element={<Navigate to="/" />} />
          </Routes>
        )}
      </main>
    </div>
  );
}

export default function App() {
  const [me, setMe] = useState<Me | null>(null);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    get<Me>('/me').then(setMe).catch(e => setError(e.message));
  }, []);
  if (error) return <main><Notice>Could not load your profile: {error}</Notice></main>;
  return (
    <MeContext.Provider value={me}>
      <Shell />
    </MeContext.Provider>
  );
}
