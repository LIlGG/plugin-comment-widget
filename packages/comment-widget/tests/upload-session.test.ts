import assert from 'node:assert/strict';
import { createServer, type ServerResponse } from 'node:http';
import { test } from 'vitest';
import { UploadSession } from '../src/utils/upload-session.ts';

async function withServer(
  fn: (
    base: string,
    state: { posts: string[]; status: string; tickets: number }
  ) => Promise<void>
) {
  const state = { posts: [] as string[], status: 'UNKNOWN', tickets: 0 };
  const server = createServer((req, res) => {
    res.setHeader('Content-Type', 'application/json');
    if (req.method === 'GET') {
      return respondWithStatus(res, state.status);
    }
    if (req.url?.endsWith('/submissions')) {
      state.tickets++;
      req.resume();
      return res.end(
        JSON.stringify({
          id: crypto.randomUUID(),
          expiresAt: new Date(Date.now() + 86400000).toISOString(),
        })
      );
    }
    state.posts.push(req.headers['x-comment-submission'] as string);
    req.resume();
    req.on('end', () => {
      res.writeHead(500);
      res.end('{}');
    });
  });
  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
  const address = server.address() as { port: number };
  try {
    await fn(`http://127.0.0.1:${address.port}`, state);
  } finally {
    await closeServer(server);
  }
}

test('unknown submission cannot be sent to Halo twice', () =>
  withServer(async (base, state) => {
    const session = new UploadSession();
    await assert.rejects(
      session.submit(base, { content: 'one' }, ['upload'], {}, base)
    );
    await assert.rejects(
      session.submit(base, { content: 'one' }, ['upload'], {}, base),
      /正在确认/
    );
    assert.equal(state.posts.length, 1);
  }));
test('confirmed successful submission recovers without another POST', () =>
  withServer(async (base, state) => {
    const session = new UploadSession();
    await assert.rejects(
      session.submit(base, { content: 'one' }, ['upload'], {}, base)
    );
    state.status = 'BOUND';
    assert.equal(
      await session.submit(base, { content: 'one' }, ['upload'], {}, base),
      undefined
    );
    assert.equal(state.posts.length, 1);
  }));
test('known failed submission starts a new attempt', () =>
  withServer(async (base, state) => {
    const session = new UploadSession();
    await assert.rejects(
      session.submit(base, { content: 'one' }, ['upload'], {}, base)
    );
    state.status = 'FAILED';
    await assert.rejects(
      session.submit(base, { content: 'two' }, ['upload'], {}, base)
    );
    assert.equal(state.posts.length, 2);
    assert.notEqual(state.posts[0], state.posts[1]);
  }));
test('removing all images does not bypass uncertainty protection', () =>
  withServer(async (base, state) => {
    const session = new UploadSession();
    await assert.rejects(
      session.submit(base, { content: 'one' }, ['upload'], {}, base)
    );
    await assert.rejects(
      session.submit(base, { content: 'two' }, [], {}, base),
      /正在确认/
    );
    assert.equal(state.posts.length, 1);
  }));

test('purged ticket never causes a second comment POST', () =>
  withServer(async (base, state) => {
    const session = new UploadSession();
    await assert.rejects(
      session.submit(base, { content: 'one' }, ['upload'], {}, base)
    );
    state.status = 'MISSING';
    await assert.rejects(
      session.submit(base, { content: 'one' }, ['upload'], {}, base),
      /已失效/
    );
    assert.equal(state.posts.length, 1);
  }));
test('unclaimed server ticket is reused after an uncertain response', () =>
  withServer(async (base, state) => {
    const session = new UploadSession();
    await assert.rejects(
      session.submit(base, { content: 'one' }, ['upload'], {}, base)
    );
    state.status = 'ISSUED';
    await assert.rejects(
      session.submit(base, { content: 'one' }, ['upload'], {}, base)
    );
    assert.equal(state.posts.length, 2);
    assert.equal(state.posts[0], state.posts[1]);
  }));

function respondWithStatus(response: ServerResponse, status: string) {
  if (status === 'MISSING') {
    response.statusCode = 404;
  }
  response.end(JSON.stringify({ state: status }));
}

function closeServer(server: ReturnType<typeof createServer>) {
  return new Promise<void>((resolve) => server.close(() => resolve()));
}

test('confirmed submission with changed content is not reported as success', () =>
  withServer(async (base, state) => {
    const session = new UploadSession();
    await assert.rejects(
      session.submit(base, { content: 'one' }, ['upload'], {}, base)
    );
    state.status = 'BOUND';
    await assert.rejects(
      session.submit(base, { content: 'edited' }, ['upload'], {}, base),
      /当前内容已修改/
    );
    assert.equal(state.posts.length, 1);
  }));

test('issued ticket with changed content is not posted again', () =>
  withServer(async (base, state) => {
    const session = new UploadSession();
    await assert.rejects(
      session.submit(base, { content: 'one' }, ['upload'], {}, base)
    );
    state.status = 'ISSUED';
    await assert.rejects(
      session.submit(base, { content: 'edited' }, ['upload'], {}, base),
      /恢复原内容/
    );
    assert.equal(state.posts.length, 1);
  }));

test('ordinary submission does not acquire or attach an upload ticket', () =>
  withServer(async (base, state) => {
    const session = new UploadSession();
    await assert.rejects(
      session.submit(base, { content: 'text' }, [], {}, base)
    );
    assert.equal(state.posts.length, 1);
    assert.equal(state.posts[0], undefined);
    assert.equal(state.tickets, 0);
  }));
