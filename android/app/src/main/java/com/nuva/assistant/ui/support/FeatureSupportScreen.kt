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
    "অ্যাপ খোলা — ইনস্টল থাকা যেকোনো অ্যাপ নাম দিয়ে (না থাকলে Play Store সাজেশন)",
    "অ্যাপ বন্ধ, হোম, ব্যাক, রিসেন্ট অ্যাপ",
    "ব্রাউজারে URL খোলা ও ওয়েব সার্চ",
    "YouTube-এ গান/ভিডিও সার্চ ও প্লে; Spotify থাকলে খোলা",
    "কল করা (নাম বা নম্বর — কনফার্মেশন বাধ্যতামূলক)",
    "SMS পাঠানো (কনফার্মেশন বাধ্যতামূলক)",
    "WhatsApp মেসেজ (কনফার্মেশন বাধ্যতামূলক)",
    "কন্টাক্ট খোঁজা — একাধিক মিল হলে আপনি বেছে নেবেন",
    "অ্যালার্ম ও টাইমার (বাংলা/বাংলিশ/ইংরেজি সময়)",
    "ক্যালেন্ডারে রিমাইন্ডার (কনফার্মেশনসহ, Save আপনি চাপবেন)",
    "নোট ও টু-ডু ভয়েসে লেখা (শুধু ফোনে থাকে)",
    "ব্যাটারি, সময়, তারিখ, নেটওয়ার্ক, স্টোরেজ জিজ্ঞাসা",
    "টর্চ টগল; ব্রাইটনেস/ভলিউম/DND/ওয়াইফাই/ব্লুটুথ সেটিং স্ক্রিন",
    "স্ক্রিন পড়া, ফোকাসড এলিমেন্ট পড়া (অ্যাক্সেসিবিলিটি লাগবে)",
    "নোটিফিকেশন সারসংক্ষেপ পড়া (OTP স্বয়ংক্রিয়ভাবে লুকানো)",
    "স্ক্রল/সোয়াইপ — শুধু নির্দিষ্ট লক্ষ্যে, অন্ধ ট্যাপ নয়",
)

val UNSUPPORTED_FEATURES = listOf(
    FeatureRow("বিকাশ / নগদ / রকেট / উপায় / ব্যাংকিং অ্যাপ অটোমেশন", false, "নিরাপত্তা নীতি: টাকার কাজ NUVA কখনো অটোমেট করে না (§32–§36)।"),
    FeatureRow("OTP / PIN / পাসওয়ার্ড / কার্ড নম্বর পড়া বা টাইপ করা", false, "নিরাপত্তা নীতি: কোনো সিক্রেট NUVA ছুঁবে না — স্ক্রিন পড়ার সময়ও লুকানো থাকে।"),
    FeatureRow("Telegram / Messenger / IMO / Viber / Signal মেসেজ", false, "এই অ্যাপগুলোর কম্পোজার স্ক্রিন এখনো নির্ভরযোগ্যভাবে অটোমেট করা যায় না — ভুল জায়গায় মেসেজ যেতে পারে। শুধু WhatsApp ও SMS সাপোর্টেড।"),
    FeatureRow("নোটিফিকেশনের রিপ্লাই পাঠানো", false, "নির্ভরযোগ্য রিপ্লাইয়ের জন্য প্রতি অ্যাপে আলাদা ইন্টিগ্রেশন লাগে — তৈরি হলে জানানো হবে।"),
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
