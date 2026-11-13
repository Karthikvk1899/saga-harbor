import React, { useEffect, useState } from 'react';
import { createRoot } from 'react-dom/client';
import './style.css';
import { elapsedLabel, orderIdValid, stateLabel } from './contracts';

type Saga = { id: string; orderId: string; state: string; commandId: string; version: string; updatedAtMs: string };
export function App() {
  const [key, setKey] = useState('');
  const [order, setOrder] = useState('');
  const [id, setId] = useState('');
  const [saga, setSaga] = useState<Saga | null>(null);
  const [error, setError] = useState('');
  const [now, setNow] = useState(Date.now());
  async function request(path: string, init?: RequestInit) {
    const response = await fetch('/api/v1/sagas' + path, { ...init, headers: { Authorization: 'Bearer ' + key, 'Content-Type': 'application/json', ...init?.headers } });
    if (!response.ok) throw new Error(`${response.status}: ${await response.text()}`);
    return response.json();
  }
  async function start(event: React.FormEvent) {
    event.preventDefault(); setError('');
    try { if (!orderIdValid(order)) throw new Error('Invalid order ID'); const created = await request('', { method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() }, body: JSON.stringify({ orderId: order }) }); setId(created.id); setSaga(await request('/' + created.id)); }
    catch (e) { setError(String(e)); }
  }
  async function lookup(event?: React.FormEvent) {
    event?.preventDefault(); setError('');
    try { setSaga(await request('/' + encodeURIComponent(id))); }
    catch (e) { setError(String(e)); }
  }
  useEffect(() => {
    const tick = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(tick);
  }, []);
  useEffect(() => {
    if (!saga || !['RESERVING', 'CHARGING', 'RELEASING'].includes(saga.state) || !key) return;
    const timer = window.setInterval(() => {
      request('/' + encodeURIComponent(saga.id)).then(setSaga).catch(e => setError(String(e)));
    }, 3000);
    return () => window.clearInterval(timer);
  }, [saga?.id, saga?.state, key]);
  return <main><header><span className="mark">SH</span><div><h1>Saga Harbor</h1><p>Distributed checkout workflow explorer</p></div></header>
    <section className="grid"><article><h2>1. Connect</h2><label>Local API key<input type="password" value={key} onChange={e => setKey(e.target.value)} placeholder="SAGA_API_KEY" autoComplete="off" /></label><small>Held only in this page’s memory. Reload to clear it.</small></article>
    <article><h2>2. Start a saga</h2><form onSubmit={start}><label>Order ID<input required pattern="[A-Za-z0-9_.:-]{1,128}" value={order} onChange={e => setOrder(e.target.value)} placeholder="order-1001" /></label><button>Create workflow</button></form></article>
    <article><h2>3. Inspect</h2><form onSubmit={lookup}><label>Saga UUID<input required value={id} onChange={e => setId(e.target.value)} placeholder="Paste a saga ID" /></label><button>Refresh state</button></form></article></section>
    {error && <p role="alert" className="error">{error}</p>}
    {saga && <section className="result"><div><span className="eyebrow">WORKFLOW STATE</span><h2>{stateLabel(saga.state)}</h2><p>{saga.state} · Version {saga.version} · Order {saga.orderId}</p><p>Updated {elapsedLabel(saga.updatedAtMs, now)}{['RESERVING', 'CHARGING', 'RELEASING'].includes(saga.state) ? ' · Refreshes every 3 seconds' : ''}</p></div><dl><dt>Saga ID</dt><dd>{saga.id}</dd><dt>Expected command ID</dt><dd>{saga.commandId || 'None — terminal state'}</dd></dl></section>}
    <footer>RESERVING → CHARGING → COMPLETED · charge failure → RELEASING → COMPENSATED or MANUAL_REVIEW</footer></main>;
}
createRoot(document.getElementById('root')!).render(<App />);
