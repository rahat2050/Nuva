/**
 * /api/screenshots — signed direct uploads for AccessibilityService
 * screenshots (roadmap follow-up; master prompt §18).
 *
 * POST /api/screenshots
 *   Auth: required (Supabase user JWT)
 *   Body: {} (the grant is derived from the verified user id)
 *   200  → { ok, upload: { cloud_name, api_key, timestamp, signature, folder,
 *                          upload_url, expires_at, max_bytes, allowed_formats } }
 *   401  → not signed in
 *   503  → Cloudinary not configured
 *
 * The image itself is uploaded by the client straight to Cloudinary with these
 * parameters, so CLOUDINARY_API_SECRET never reaches the app and screenshot
 * bytes never pass through the serverless function.
 */

export const config = {
  maxDuration: 10,
};

import { defineHandler, ok } from '../../lib/http.js';
import { requireUser, resolveIdentity } from '../../lib/auth.js';
import { cloudinaryConfiguredOrThrow, signScreenshotUpload } from '../../lib/cloudinary.js';

export default defineHandler({
  name: 'screenshots',
  methods: ['POST'],
  rateLimit: true,
  handler: async ({ req, logger, env }) => {
    const identity = await resolveIdentity(req, logger, { env });
    const userId = requireUser(identity);

    cloudinaryConfiguredOrThrow(env);

    const upload = signScreenshotUpload(userId, env);
    logger.info('screenshot upload grant issued', { folder: upload.folder, expires_at: upload.expires_at });
    return ok({ ok: true, upload });
  },
});
