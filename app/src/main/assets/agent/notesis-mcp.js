#!/usr/bin/env node
// Notesis MCP bridge.
//
// Agents (Claude Code, Codex, OpenCode) start an MCP server as a program and talk to it
// on stdin and stdout, a JSON message per line. This is that program: it passes each
// message to the Notesis app's agent connection over HTTP on this device, and its
// answer back. Nothing else - no dependencies, no state but the session the app gives.
//
// Where and how: ~/.notesis/url and ~/.notesis/token, written by the setup script, read
// again for every message, so a new token from the app is picked up without a restart.
// NOTESIS_URL, NOTESIS_URL_FILE and NOTESIS_TOKEN_FILE stand in for them when set.
'use strict';
const http = require('http');
const fs = require('fs');
const os = require('os');
const path = require('path');
const readline = require('readline');

const home = path.join(os.homedir(), '.notesis');
const readSetting = (name) => {
  const file = process.env[`NOTESIS_${name.toUpperCase()}_FILE`] || path.join(home, name);
  try { return fs.readFileSync(file, 'utf8').trim(); } catch (e) { return ''; }
};
const address = () => new URL(process.env.NOTESIS_URL || readSetting('url') || 'http://127.0.0.1:47821/mcp');

let session = null;
let pending = 0;
let closed = false;

const write = (message) => process.stdout.write(JSON.stringify(message) + '\n');

/** The ids a message is waiting on an answer for: a request's, or each in a batch. */
const idsOf = (message) => {
  const all = Array.isArray(message) ? message : [message];
  return all.filter((m) => m && typeof m === 'object' && 'method' in m && 'id' in m && m.id !== null).map((m) => m.id);
};

const fail = (message, text) => {
  for (const id of idsOf(message)) write({ jsonrpc: '2.0', id, error: { code: -32000, message: text } });
};

const post = (body) => new Promise((resolve, reject) => {
  const url = address();
  const headers = {
    'Content-Type': 'application/json',
    'Accept': 'application/json, text/event-stream',
    'Content-Length': Buffer.byteLength(body),
    'Authorization': `Bearer ${readSetting('token')}`,
  };
  if (session) headers['Mcp-Session-Id'] = session;
  const request = http.request({ hostname: url.hostname, port: url.port || 80, path: url.pathname, method: 'POST', headers }, (response) => {
    const chunks = [];
    response.on('data', (c) => chunks.push(c));
    response.on('end', () => resolve({ status: response.statusCode, headers: response.headers, text: Buffer.concat(chunks).toString('utf8') }));
  });
  // A change may wait for the owner's answer for two minutes; give it more than that.
  request.setTimeout(5 * 60 * 1000, () => request.destroy(new Error('No answer from Notesis in time.')));
  request.on('error', reject);
  request.end(body);
});

const handle = async (line) => {
  let message;
  try { message = JSON.parse(line); } catch (e) {
    write({ jsonrpc: '2.0', id: null, error: { code: -32700, message: 'Parse error' } });
    return;
  }
  try {
    const answer = await post(JSON.stringify(message));
    if (answer.headers['mcp-session-id']) session = answer.headers['mcp-session-id'];
    if (answer.status === 401) return fail(message, 'Notesis refused the token. Copy the token update command from Notesis (Agent connection) and run it.');
    if (answer.status === 403) return fail(message, 'Notesis refused the connection.');
    if (answer.status === 202 || !answer.text) return;
    let parsed;
    try { parsed = JSON.parse(answer.text); } catch (e) { return fail(message, `Notesis answered ${answer.status} without JSON.`); }
    write(parsed);
  } catch (e) {
    fail(message, 'Notesis is not reachable. Open the app and turn on Agent connection. (' + e.message + ')');
  }
};

const lines = readline.createInterface({ input: process.stdin, terminal: false });
lines.on('line', (raw) => {
  const line = raw.trim();
  if (!line) return;
  pending++;
  handle(line).finally(() => { pending--; if (closed && pending === 0) process.exit(0); });
});
lines.on('close', () => { closed = true; if (pending === 0) process.exit(0); });
