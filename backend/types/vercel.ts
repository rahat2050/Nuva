import type { IncomingMessage, ServerResponse } from 'node:http';

/**
 * The small Vercel Node request/response surface NUVA actually consumes.
 * Keeping these structural types local avoids shipping/installing the full
 * dev-only @vercel/node runtime graph just for interfaces.
 */
export interface VercelRequest extends IncomingMessage {
  body: unknown;
  query: Record<string, string | string[] | undefined>;
  cookies: Record<string, string>;
}

export interface VercelResponse extends ServerResponse {
  status(statusCode: number): VercelResponse;
  json(body: unknown): VercelResponse;
  send(body?: unknown): VercelResponse;
  redirect(statusOrUrl: number | string, url?: string): VercelResponse;
}
