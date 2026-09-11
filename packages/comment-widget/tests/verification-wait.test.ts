import assert from 'node:assert/strict';
import test from 'node:test';
import { VerificationWait } from '../src/utils/verification-wait.ts';

test('allows manual interaction beyond the automatic timeout and resolves after success', async (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  let timedOut = false;
  const wait = new VerificationWait(() => {
    timedOut = true;
  });
  const result = wait.wait(false);
  t.mock.timers.tick(59000);
  wait.setInteractive(true);
  t.mock.timers.tick(120000);
  assert.equal(timedOut, false);
  wait.finish('verified');
  assert.equal(await result, 'verified');
});

test('starts a fresh bounded wait when manual interaction ends', async (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  let timedOut = false;
  const wait = new VerificationWait(() => {
    timedOut = true;
  });
  const result = wait.wait(true);
  t.mock.timers.tick(120000);
  assert.equal(timedOut, false);
  wait.setInteractive(false);
  t.mock.timers.tick(60000);
  assert.equal(await result, '');
  assert.equal(timedOut, true);
});

test('cancellation clears pending timers and prevents late timeout callbacks', async (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const wait = new VerificationWait(() => assert.fail('Unexpected timeout'));
  const result = wait.wait(false);
  wait.finish('');
  assert.equal(await result, '');
  t.mock.timers.tick(120000);
});
