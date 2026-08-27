package com.nuva.assistant.ui.support

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nuva.assistant.R

/**
 * Supported / unsupported feature list (v1.1). NUVA never pretends: what is
 * automated is listed with a check, what is not is listed with the exact
 * reason — including the strict security exclusions (banking/payment) that
 * will never be supported.
 */
data class FeatureRow(val name: String, val supported: Boolean, val reason: String? = null)

val SUPPORTED_FEATURES = listOf(
    "১২,২৫০টি audited natural command form — polite/ASR/Bangla/Banglish/English variant ও ৬-step multi-command plan",
    "অ্যাপ খোলা/Play Store suggestion; non-financial app-এর system uninstall prompt (final decision user); financial/NUVA blocked",
    "অ্যাপ বন্ধ, হোম, ব্যাক, রিসেন্ট অ্যাপ; ফাইন্যান্সিয়াল অ্যাপেও স্ক্রল/নেভিগেশন",
    "ব্রাউজারে URL খোলা ও ওয়েব সার্চ; আবহাওয়া/খবর/লাইভ স্কোর/ট্রাফিক/রেটের বর্তমান তথ্য লাইভ ওয়েবে খোঁজা",
    "YouTube-এ গান/ভিডিও সার্চ ও প্লে; Spotify থাকলে খোলা",
    "মিউজিক কন্ট্রোল — pause/resume/next/previous (চলমান MediaSession দিয়ে)",
    "ভলিউম সরাসরি বাড়ানো/কমানো/মিউট",
    "ক্যামেরা খোলা (ছবি/ভিডিও মোড); ছবি তোলার কমান্ডে ক্যাপচার স্ক্রিন — শাটার আপনার হাতে",
    "Android picker দিয়ে selected file open/read/rename/copy/move/delete এবং সর্বোচ্চ ১০ file/photo/video multi-share",
    "Selected photo/video view/share এবং selected photo installed editor-এ edit/crop handoff — final Save আপনি করবেন",
    "কল করা (নাম বা নম্বর — কনফার্মেশন বাধ্যতামূলক)",
    "SMS ও WhatsApp — কনফার্মেশনের পর সরাসরি পাঠানো",
    "Telegram / Messenger / Signal / Viber / IMO — মেসেজ লেখা বসিয়ে অ্যাপ খোলে, Send আপনি চাপবেন",
    "Email recipient/subject/body prefill; picker থেকে সর্বোচ্চ ১০ attachment — final Send আপনি চাপবেন",
    "Generic text share, new contact draft এবং exact contact picker view/edit — destination/Save user-controlled",
    "Facebook/Instagram/X/LinkedIn/Reddit/Threads/TikTok text post draft — visible compose, final Post user",
    "MMS/message composer with one picker attachment এবং voicemail dialer — final Send/call user",
    "Passport/NID/birth/driving/visa/admission/job/doctor/hotel/flight/courier local form draft + official portal handoff",
    "Persistent email/SMS draft reminder — once/daily/weekly, reboot restore, voice list/cancel; tap করলে draft, auto-Send নয়",
    "Explicit clipboard copy/read/clear — confirmation, bounded/redacted text; কোনো monitoring/history নয়",
    "Rich calendar event draft — title/time/duration/location/description/attendee; final Save user",
    "কন্টাক্ট খোঁজা — একাধিক মিল হলে আপনি বেছে নেবেন",
    "অ্যালার্ম ও টাইমার (বাংলা/বাংলিশ/ইংরেজি সময়)",
    "ক্যালেন্ডারে রিমাইন্ডার (কনফার্মেশনসহ, Save আপনি চাপবেন)",
    "নোট, টু-ডু, শপিং/বাজারের তালিকা ও খরচের নোট ভয়েসে লেখা এবং আবার পড়ে শোনানো (শুধু ফোনে থাকে)",
    "অফলাইন হিসাব — +, −, ×, ÷, bracket, power, square root, factorial, percentage, discount, VAT/tip, bill split",
    "দৈনন্দিন calculator — BMI/BMR, পানি, EMI/interest, profit-loss, unit price, savings, mileage/fuel cost/ETA, statistics, ratio, grade, geometry, download time, date, random",
    "৬০০টি sourced daily skill — ১০০ broad shortcut + ৫০০ precise entity×task skill: local service, সরকারি আবেদন, learning ও product help",
    "১,০০০+ command form: length/weight/volume/area/বাংলাদেশি জমি/speed/time/data/temperature/energy/pressure conversion",
    "ব্যাটারি, সময়, তারিখ, নেটওয়ার্ক, স্টোরেজ জিজ্ঞাসা — ফোন থেকেই তাৎক্ষণিক উত্তর, ইন্টারনেট/AI লাগে না",
    "১৬+ exact system panel: mobile data/airplane/location/hotspot/NFC/VPN/battery/default app/date/language/storage/privacy/security/cast/print/caption",
    "যেকোনো installed app-এর App Info, app-specific notification settings ও Play Store page",
    "টর্চ/ভলিউম direct; DND access থাকলে direct—অন্য secure toggle final user-controlled",
    "স্ক্রিন পড়া, ফোকাসড এলিমেন্ট পড়া (অ্যাক্সেসিবিলিটি লাগবে)",
    "নোটিফিকেশন summary/app/reply; confirmation-এর পর এক safe notification dismiss বা exact official Mark as read",
    "স্ক্রল/সোয়াইপ — শুধু নির্দিষ্ট লক্ষ্যে, অন্ধ ট্যাপ নয়",
)

val UNSUPPORTED_FEATURES = listOf(
    FeatureRow("টাকা পাঠানো / ক্যাশ আউট / ব্যাংক ট্রান্সফার / পেমেন্ট / রিচার্জ / পেমেন্ট কনফার্ম করা", false, "নীতি (LEVEL 3): ফাইন্যান্সিয়াল ট্রানজেকশন NUVA কখনো অটোমেট করে না — কনফার্মেশনও অফার করে না, সরাসরি বলে দেয়। অ্যাপ খুলে আপনি নিজে করতে পারবেন।"),
    FeatureRow("ফাইন্যান্সিয়াল অ্যাপের স্ক্রিন পড়া ও বাটনে ট্যাপ/টাইপ", false, "নীতি (LEVEL 2/3): OTP/PIN/ব্যালেন্স স্ক্রিন আলাদা করা নির্ভরযোগ্যভাবে সম্ভব নয়, তাই ওয়ালেট/ব্যাংক অ্যাপের সামনে স্ক্রিন-রিড ও ট্যাপ/টাইপ বন্ধ থাকে। অ্যাপ খোলা, স্ক্রল, back/home চলে।"),
    FeatureRow("OTP / PIN / পাসওয়ার্ড / CVV / কার্ড নম্বর পড়া, টাইপ বা সেভ করা", false, "নীতি (LEVEL 2): কোনো সিক্রেট NUVA ছুঁবে না — পাসওয়ার্ড ফিল্ড স্কিপ হয়, OTP-সদৃশ কোড সব জায়গায় লুকানো থাকে।"),
    FeatureRow("Official RemoteInput নেই এমন notification-এর reply", false, "v2.3-এ RemoteInput action থাকলে confirmation-এর পর reply চলে; action না দিলে NUVA app/UI আন্দাজ করে reply করে না।"),
    FeatureRow("Storage/gallery জুড়ে background search, arbitrary/model path বা automatic bulk edit/delete", false, "User-selected target operation এখন supported; কিন্তু NUVA broad storage scan, hidden path বা target না বেছে destructive operation করে না।"),
    FeatureRow("সব অ্যাপে অন্ধ ট্যাপ/বোতাম চাপা", false, "NUVA শুধু স্ক্রিনে খুঁজে পাওয়া নির্দিষ্ট বাটনে কাজ করে; UI বদলালে নিরাপদে ফেল করে জানায়।"),
)

@Composable
fun FeatureSupportScreen() {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text(stringResource(R.string.support_title), style = MaterialTheme.typography.headlineSmall)
            Text(
                stringResource(R.string.support_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
        }

        item {
            Text(stringResource(R.string.support_supported), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
        }
        items(SUPPORTED_FEATURES) { feature ->
            Card(Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                    )
                    Text(feature, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        item {
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.support_unsupported), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
        }
        items(UNSUPPORTED_FEATURES) { row ->
            Card(Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        Icons.Filled.Cancel,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Column {
                        Text(row.name, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            row.reason.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}
