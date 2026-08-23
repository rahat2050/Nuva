/**
 * Known app aliases → canonical slug + Android package hint.
 *
 * Used to (a) enrich OPEN_APP/CLOSE_APP with a package hint and (b) let the
 * deterministic fallback parser resolve app names offline. The Android layer
 * still resolves packages itself in PHRASE 2 (PackageManager is authoritative);
 * this is only a hint, never a guarantee.
 *
 * Aliases cover English, Bangla script and common Banglish/ASR spellings (§15).
 */

export interface KnownApp {
  slug: string;
  /** Correctly cased brand name, used when NUVA speaks (§25). */
  label: string;
  packageName: string | null;
  aliases: string[];
  /** Financial/sensitive apps are flagged so risk.ts can escalate (§11). */
  sensitive?: boolean;
}

export const KNOWN_APPS: KnownApp[] = [
  { slug: 'youtube', label: 'YouTube', packageName: 'com.google.android.youtube', aliases: ['youtube', 'you tube', 'utube', 'ইউটিউব', 'yt'] },
  { slug: 'whatsapp', label: 'WhatsApp', packageName: 'com.whatsapp', aliases: ['whatsapp', 'whats app', 'wattsapp', 'হোয়াটসঅ্যাপ', 'হোয়াটসাপ'] },
  { slug: 'facebook', label: 'Facebook', packageName: 'com.facebook.katana', aliases: ['facebook', 'fb', 'ফেসবুক'] },
  { slug: 'messenger', label: 'Messenger', packageName: 'com.facebook.orca', aliases: ['messenger', 'mesenger', 'ম্যাসেঞ্জার', 'মেসেঞ্জার'] },
  { slug: 'instagram', label: 'Instagram', packageName: 'com.instagram.android', aliases: ['instagram', 'insta', 'ইনস্টাগ্রাম'] },
  { slug: 'telegram', label: 'Telegram', packageName: 'org.telegram.messenger', aliases: ['telegram', 'টেলিগ্রাম'] },
  { slug: 'imo', label: 'imo', packageName: 'com.imo.android.imoim', aliases: ['imo', 'ইমো'] },
  { slug: 'tiktok', label: 'TikTok', packageName: 'com.zhiliaoapp.musically', aliases: ['tiktok', 'tik tok', 'টিকটক'] },
  { slug: 'chrome', label: 'Chrome', packageName: 'com.android.chrome', aliases: ['chrome', 'browser', 'google chrome', 'ক্রোম', 'ব্রাউজার'] },
  { slug: 'gmail', label: 'Gmail', packageName: 'com.google.android.gm', aliases: ['gmail', 'mail', 'email', 'জিমেইল', 'ইমেইল'] },
  { slug: 'maps', label: 'Maps', packageName: 'com.google.android.apps.maps', aliases: ['maps', 'google maps', 'map', 'ম্যাপ', 'গুগল ম্যাপ'] },
  { slug: 'play_store', label: 'Play Store', packageName: 'com.android.vending', aliases: ['play store', 'playstore', 'google play', 'প্লে স্টোর'] },
  { slug: 'phone', label: 'Phone', packageName: null, aliases: ['phone', 'dialer', 'call app', 'ফোন', 'ডায়ালার'] },
  { slug: 'messages', label: 'Messages', packageName: null, aliases: ['messages', 'sms', 'message app', 'text message', 'এসএমএস', 'মেসেজ'] },
  { slug: 'contacts', label: 'Contacts', packageName: null, aliases: ['contacts', 'contact', 'কন্টাক্ট', 'কন্টাক্টস'] },
  { slug: 'camera', label: 'Camera', packageName: null, aliases: ['camera', 'ক্যামেরা'] },
  { slug: 'gallery', label: 'Gallery', packageName: null, aliases: ['gallery', 'photos', 'গ্যালারি', 'ছবি'] },
  { slug: 'clock', label: 'Clock', packageName: null, aliases: ['clock', 'alarm', 'alarm clock', 'ঘড়ি', 'অ্যালার্ম'] },
  { slug: 'calculator', label: 'Calculator', packageName: null, aliases: ['calculator', 'calc', 'ক্যালকুলেটর'] },
  { slug: 'calendar', label: 'Calendar', packageName: null, aliases: ['calendar', 'ক্যালেন্ডার'] },
  { slug: 'settings', label: 'Settings', packageName: 'com.android.settings', aliases: ['settings', 'setting', 'সেটিংস', 'সেটিং'] },
  { slug: 'files', label: 'Files', packageName: null, aliases: ['files', 'file manager', 'ফাইল', 'ফাইল ম্যানেজার'] },
  { slug: 'spotify', label: 'Spotify', packageName: 'com.spotify.music', aliases: ['spotify', 'স্পটিফাই'] },
  { slug: 'netflix', label: 'Netflix', packageName: 'com.netflix.mediaclient', aliases: ['netflix', 'নেটফ্লিক্স'] },
  { slug: 'zoom', label: 'Zoom', packageName: 'us.zoom.videomeetings', aliases: ['zoom', 'জুম'] },
  // Financial apps are resolvable but always escalate to HIGH risk (§11).
  { slug: 'bkash', label: 'bKash', packageName: 'com.bKash.customerapp', aliases: ['bkash', 'bikash', 'বিকাশ'], sensitive: true },
  { slug: 'nagad', label: 'Nagad', packageName: 'com.konasl.nagad', aliases: ['nagad', 'নগদ'], sensitive: true },
  { slug: 'rocket', label: 'Rocket', packageName: null, aliases: ['rocket', 'dbbl rocket', 'রকেট'], sensitive: true },
];

const ALIAS_INDEX: Map<string, KnownApp> = (() => {
  const index = new Map<string, KnownApp>();
  for (const app of KNOWN_APPS) {
    index.set(app.slug, app);
    for (const alias of app.aliases) index.set(alias.toLowerCase(), app);
  }
  return index;
})();

/** Longest aliases first so "google maps" wins over "maps" during text scanning. */
const SORTED_ALIASES: Array<{ alias: string; app: KnownApp }> = [...ALIAS_INDEX.entries()]
  .map(([alias, app]) => ({ alias, app }))
  .sort((a, b) => b.alias.length - a.alias.length);

export function resolveApp(name: string): KnownApp | null {
  return ALIAS_INDEX.get(name.trim().toLowerCase()) ?? null;
}

export function packageHintFor(name: string): string | null {
  return resolveApp(name)?.packageName ?? null;
}

export function isSensitiveApp(name: string): boolean {
  return resolveApp(name)?.sensitive === true;
}

/** Finds the first known app mentioned anywhere in a phrase. */
export function findAppInText(text: string): KnownApp | null {
  const haystack = ` ${text.toLowerCase()} `;
  for (const { alias, app } of SORTED_ALIASES) {
    if (alias.length < 2) continue;
    // Latin aliases need word boundaries; Bangla script has no case/word-char class.
    const isLatin = /^[a-z0-9 ._-]+$/.test(alias);
    if (isLatin) {
      const pattern = new RegExp(`(^|[^a-z0-9])${alias.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}([^a-z0-9]|$)`);
      if (pattern.test(haystack)) return app;
    } else if (haystack.includes(alias)) {
      return app;
    }
  }
  return null;
}

/** Brand-correct display name for speech; falls back to title case. */
export function appLabel(name: string): string {
  const known = resolveApp(name);
  if (known) return known.label;
  return name
    .split(/[\s_-]+/)
    .filter((part) => part.length > 0)
    .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
    .join(' ');
}
