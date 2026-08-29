/**
 * LOCAL HARNESS ONLY — excluded from deploys via .vercelignore.
 *
 * Adapts Node's http req/res to the small slice of the @vercel/node
 * VercelRequest/VercelResponse surface that NUVA's handlers actually use, so the
 * exact same handler files can be run locally and unit-tested without the Vercel
 * CLI.
 */
import type { IncomingMessage, ServerResponse } from 'node:http';
import type { VercelRequest, VercelResponse } from '../types/vercel.js';

const MAX_BODY_BYTES = 64 * 1024;

export type VercelHandler = (req: VercelRequest, res: VercelResponse) => void | Promise<void>;

async function readRawBody(req: IncomingMessage): Promise<string> {
  const chunks: Buffer[] = [];
  let total = 0;
  for await (const chunk of req) {
    const buf = Buffer.isBuffer(chunk) ? chunk : Buffer.from(String(chunk));
    total += buf.length;
    if (total > MAX_BODY_BYTES) break;
    chunks.push(buf);
  }
  return Buffer.concat(chunks).toString('utf8');
}

function buildQuery(url: URL): Record<string, string | string[]> {
  const query: Record<string, string | string[]> = {};
  for (const key of new Set(url.searchParams.keys())) {
    const values = url.searchParams.getAll(key);
    query[key] = values.length > 1 ? values : (values[0] ?? '');
  }
  return query;
}

function parseCookies(header: string | undefined): Record<string, string> {
  if (!header) return {};
  const cookies: Record<string, string> = {};
  for (const part of header.split(';')) {
    const index = part.indexOf('=');
    if (index === -1) continue;
    const name = part.slice(0, index).trim();
    if (name.length > 0) cookies[name] = decodeURIComponent(part.slice(index + 1).trim());
  }
  return cookies;
}

/** Wraps a ServerResponse with Vercel's status()/json()/send() helpers. */
export function enhanceResponse(res: ServerResponse): VercelResponse {
  const enhanced = res as ServerResponse & Partial<VercelResponse>;

  enhanced.status = (statusCode: number) => {
    res.statusCode = statusCode;
    return enhanced as VercelResponse;
  };

  enhanced.json = (body: unknown) => {
    if (!res.headersSent) res.setHeader('Content-Type', 'application/json; charset=utf-8');
    res.end(JSON.stringify(body));
    return enhanced as VercelResponse;
  };

  enhanced.send = (body: unknown) => {
    if (typeof body === 'string' || Buffer.isBuffer(body)) {
      res.end(body);
    } else if (body === undefined || body === null) {
      res.end();
    } else {
      if (!res.headersSent) res.setHeader('Content-Type', 'application/json; charset=utf-8');
      res.end(JSON.stringify(body));
    }
    return enhanced as VercelResponse;
  };

  enhanced.redirect = (statusOrUrl: number | string, maybeUrl?: string) => {
    const status = typeof statusOrUrl === 'number' ? statusOrUrl : 307;
    const location = typeof statusOrUrl === 'string' ? statusOrUrl : (maybeUrl ?? '/');
    res.statusCode = status;
    res.setHeader('Location', location);
    res.end();
    return enhanced as VercelResponse;
  };

  return enhanced as VercelResponse;
}

/** Wraps an IncomingMessage with Vercel's query/body/cookies properties. */
export async function enhanceRequest(req: IncomingMessage, url: URL): Promise<VercelRequest> {
  const enhanced = req as IncomingMessage & Partial<VercelRequest>;
  enhanced.query = buildQuery(url);
  enhanced.cookies = parseCookies(req.headers.cookie);

  if (req.method && !['GET', 'HEAD', 'DELETE', 'OPTIONS'].includes(req.method)) {
    const raw = await readRawBody(req);
    // Handlers accept a string body and parse it themselves (see lib/http.ts).
    enhanced.body = raw;
  }

  return enhanced as VercelRequest;
}

/**
 * Test helper: invoke a handler with a synthetic request and capture the result.
 */
export interface InvokeResult {
  status: number;
  headers: Record<string, string>;
  body: unknown;
  raw: string;
}

export async function invokeForTest(
  handler: VercelHandler,
  init: {
    method?: string;
    url?: string;
    headers?: Record<string, string>;
    body?: unknown;
  } = {},
): Promise<InvokeResult> {
  const method = init.method ?? 'GET';
  const url = new URL(init.url ?? '/', 'http://localhost');
  const headers: Record<string, string> = {};
  for (const [key, value] of Object.entries(init.headers ?? {})) headers[key.toLowerCase()] = value;

  const rawBody = init.body === undefined ? undefined : typeof init.body === 'string' ? init.body : JSON.stringify(init.body);
  if (rawBody !== undefined) {
    headers['content-type'] ??= 'application/json';
    headers['content-length'] ??= String(Buffer.byteLength(rawBody));
  }

  const req = {
    method,
    url: `${url.pathname}${url.search}`,
    headers,
    query: buildQuery(url),
    cookies: {},
    body: rawBody,
    socket: { remoteAddress: '127.0.0.1' },
  } as unknown as VercelRequest;

  const captured: InvokeResult = { status: 200, headers: {}, body: undefined, raw: '' };
  const sentinel = {};
  let resolveDone: (value: unknown) => void = () => undefined;
  const done = new Promise((resolve) => {
    resolveDone = resolve;
  });

  const res = {
    statusCode: 200,
    headersSent: false,
    writableEnded: false,
    // SSE/streaming handlers write chunks instead of one JSON body.
    write(chunk: unknown) {
      const text = typeof chunk === 'string' ? chunk : Buffer.from(String(chunk)).toString('utf8');
      captured.raw += text;
      captured.body = captured.raw;
      (res as unknown as { headersSent: boolean }).headersSent = true;
      return res;
    },
    setHeader(name: string, value: string | number | readonly string[]) {
      captured.headers[name.toLowerCase()] = Array.isArray(value) ? value.join(', ') : String(value);
      return res;
    },
    getHeader(name: string) {
      return captured.headers[name.toLowerCase()];
    },
    status(code: number) {
      captured.status = code;
      return res;
    },
    json(body: unknown) {
      captured.body = body;
      captured.raw = JSON.stringify(body);
      resolveDone(sentinel);
      return res;
    },
    send(body: unknown) {
      captured.raw = typeof body === 'string' ? body : JSON.stringify(body);
      captured.body = body;
      resolveDone(sentinel);
      return res;
    },
    end(body?: unknown) {
      if (typeof body === 'string') captured.raw = body;
      (res as unknown as { writableEnded: boolean }).writableEnded = true;
      resolveDone(sentinel);
      return res;
    },
  } as unknown as VercelResponse;

  await Promise.all([handler(req, res), done]);
  return captured;
}
