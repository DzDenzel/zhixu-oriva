import test from 'node:test';
import assert from 'node:assert/strict';
import { createHost, HostError } from '../index.js';

test('all seven operations cross the real string boundary with exact arguments', () => {
  const sent = [];
  const host = createHost({ postMessage: raw => { assert.equal(typeof raw, 'string'); sent.push(JSON.parse(raw)); return '{"ok":true,"data":null}'; } });
  host.query.today('2026-10-08'); host.query.plan(19); host.query.report('week');
  host.overlay('专注中', 1000); host.home(); host.haptic(); host.feedback('saved');
  assert.deepEqual(sent, [
    { op: 'query.today', args: { date: '2026-10-08' } }, { op: 'query.plan', args: { id: 19 } },
    { op: 'query.report', args: { period: 'week' } }, { op: 'overlay', args: { text: '专注中', displayMillis: 1000 } },
    { op: 'home', args: {} }, { op: 'haptic', args: {} }, { op: 'feedback', args: { code: 'saved' } },
  ]);
});
test('capability denial string from native host is preserved', () => {
  const client = createHost({ postMessage: () => '{"ok":false,"error":"Undeclared QUERY_PLAN","code":"UNDECLARED"}' });
  assert.throws(() => client.query.plan(1), error => error instanceof HostError && error.message === 'Undeclared QUERY_PLAN');
});
test('invalid or imprecise native-bound arguments never invoke JNI', () => {
  let invoked = 0; const client = createHost({ postMessage: () => { invoked++; return '{"ok":true,"data":null}'; } });
  for (const call of [() => client.query.plan(1.5), () => client.query.plan(Number.MAX_SAFE_INTEGER + 1),
    () => client.query.plan(0), () => client.query.report('year'), () => client.query.today('08/10/26'),
    () => client.overlay('text', 0), () => client.feedback(''), () => client.request({ op: 'fetch', args: {} })]) assert.throws(call, HostError);
  assert.equal(invoked, 0);
});
test('malformed or object-valued JNI responses fail explicitly', () => {
  for (const raw of [{ ok: true, data: null }, 'bad json', '{}', '{"ok":"true","data":null}', '{"ok":false,"error":{}}', '{"ok":true}']) {
    const client = createHost({ postMessage: () => raw }); assert.throws(() => client.query.today(), HostError);
  }
});
test('today defaults to host current date and no hidden network/filesystem helpers exist', () => {
  const sent = []; const client = createHost({ postMessage: raw => { sent.push(JSON.parse(raw)); return '{"ok":true,"data":null}'; } });
  assert.equal(client.query.today(), null); assert.deepEqual(sent, [{ op: 'query.today', args: {} }]);
  assert.equal(client.fetch, undefined); assert.equal(client.fs, undefined); assert.equal(client.context, undefined);
});
