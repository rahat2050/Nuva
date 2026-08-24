/**
 * Cloudinary screenshot uploads (roadmap follow-up, master prompt §18).
 *
 * SECURITY MODEL — the API secret never leaves the server:
 *   1. The Android app asks POST /api/screenshots for a signed upload grant.
 *   2. The server signs a tiny parameter set (folder + timestamp) with
 *      CLOUDINARY_API_SECRET using Cloudinary's SHA-1 signature algorithm.
 *   3. The app uploads the image DIRECTLY to Cloudinary with those params.
 *      A grant is bound to one user's folder and expires with its timestamp.
 *
 * Screenshot pixels therefore never flow through the Vercel function (body
 * limits stay small), and a leaked grant can only ever write images into the
 * signing user's own folder for a few minutes.
 */
import { createHash } from 'node:crypto';
import { cloudinaryConfigured, getEnv, type NuvaEnv } from './env.js';
import { NuvaError } from './errors.js';

/** Grants are short-lived; Cloudinary rejects stale timestamps. */
export const SIGNATURE_TTL_SECONDS = 300;
/** Documentation-only client guidance (enforced by Cloudinary account rules). */
export const MAX_UPLOAD_BYTES = 10 * 1024 * 1024;
export const ALLOWED_FORMATS = ['png', 'jpg', 'jpeg', 'webp'] as const;

export interface ScreenshotUploadGrant {
  cloud_name: string;
  api_key: string;
  timestamp: number;
  /** Cloudinary signature over { folder, timestamp }. */
  signature: string;
  /** Per-user folder: nuva/<user_id>/screenshots. */
  folder: string;
  upload_url: string;
  /** Unix seconds after which the grant should no longer be used. */
  expires_at: number;
  max_bytes: number;
  allowed_formats: readonly string[];
  usage:
    | 'multipart form fields: file, api_key, timestamp, folder, signature — POSTed to upload_url'
    | string;
}

export function cloudinaryConfiguredOrThrow(env: NuvaEnv = getEnv()): void {
  if (!cloudinaryConfigured(env)) {
    throw new NuvaError(
      'NOT_CONFIGURED',
      'Screenshot uploads are not configured (CLOUDINARY_CLOUD_NAME / CLOUDINARY_API_KEY / CLOUDINARY_API_SECRET)',
    );
  }
}

/**
 * Cloudinary signature: SHA-1 over the parameters sorted alphabetically,
 * joined as `k=v` with `&`, with the API secret appended — no separator.
 * See https://cloudinary.com/documentation/upload_images#generating_authentication_signatures
 */
export function cloudinarySignature(
  params: Record<string, string | number>,
  apiSecret: string,
): string {
  const toSign = Object.keys(params)
    .sort()
    .map((key) => `${key}=${params[key]}`)
    .join('&');
  return createHash('sha1').update(`${toSign}${apiSecret}`).digest('hex');
}

/** Builds a signed direct-upload grant for one user. Never returns the secret. */
export function signScreenshotUpload(
  userId: string,
  env: NuvaEnv = getEnv(),
  nowSeconds: number = Math.floor(Date.now() / 1000),
): ScreenshotUploadGrant {
  if (!cloudinaryConfigured(env) || !env.cloudinary) {
    throw new NuvaError('NOT_CONFIGURED', 'Cloudinary is not configured');
  }
  const { cloudName, apiKey, apiSecret } = env.cloudinary;
  const folder = `nuva/${userId}/screenshots`;
  const timestamp = nowSeconds;
  const signature = cloudinarySignature({ folder, timestamp }, apiSecret);

  return {
    cloud_name: cloudName,
    api_key: apiKey,
    timestamp,
    signature,
    folder,
    upload_url: `https://api.cloudinary.com/v1_1/${cloudName}/image/upload`,
    expires_at: timestamp + SIGNATURE_TTL_SECONDS,
    max_bytes: MAX_UPLOAD_BYTES,
    allowed_formats: ALLOWED_FORMATS,
    usage:
      'multipart form fields: file, api_key, timestamp, folder, signature — POSTed to upload_url',
  };
}
