package com.nuva.assistant.command

/**
 * One hundred safe, sourced daily-life shortcuts.
 *
 * These skills do not pretend to know changing facts and never execute an
 * external side effect. A match becomes a normal [NuvaAction.SearchWeb], so
 * weather, schedules, public services, health information, prices and local
 * businesses come from current web sources. Transaction and credential
 * requests are still rejected before this registry is consulted.
 */
object DailySkillRegistry {

    data class Skill(
        val id: String,
        val category: String,
        val defaultQuery: String,
        val aliases: List<String>,
    )

    data class Match(val skill: Skill, val query: String)

    private fun skill(id: String, category: String, defaultQuery: String, vararg aliases: String) =
        Skill(id, category, defaultQuery, (listOf(defaultQuery) + aliases).map { it.lowercase() }.distinct())

    val skills: List<Skill> = listOf(
        // Health & care — 1..15
        skill("nearby_hospital", "health", "nearby hospital", "hospital near me", "kacher hospital", "কাছের হাসপাতাল"),
        skill("nearby_pharmacy", "health", "nearby pharmacy", "pharmacy near me", "kacher pharmacy", "কাছের ফার্মেসি"),
        skill("doctor_appointment", "health", "doctor appointment information", "doctor serial", "daktar appointment", "ডাক্তারের সিরিয়াল"),
        skill("ambulance_number", "health", "local ambulance emergency number", "ambulance number", "ambulance service", "অ্যাম্বুলেন্স নম্বর"),
        skill("blood_bank", "health", "nearby blood bank", "blood donor service", "blood bank near me", "ব্লাড ব্যাংক"),
        skill("first_aid", "health", "first aid guidance", "first aid", "prothomik chikitsha", "প্রাথমিক চিকিৎসা"),
        skill("medicine_information", "health", "medicine information", "medicine side effects", "oshudh er tothyo", "ওষুধের তথ্য"),
        skill("symptom_information", "health", "symptom information", "symptom checker information", "rog er lokkhon", "রোগের লক্ষণ"),
        skill("vaccination_center", "health", "nearby vaccination center", "vaccine center", "tika kendro", "টিকা কেন্দ্র"),
        skill("diagnostic_center", "health", "nearby diagnostic center", "diagnostic test near me", "test center", "ডায়াগনস্টিক সেন্টার"),
        skill("mental_health_help", "health", "mental health support", "counselling near me", "mental health helpline", "মানসিক স্বাস্থ্য সহায়তা"),
        skill("dentist_nearby", "health", "nearby dentist", "dental clinic near me", "dant er doctor", "দাঁতের ডাক্তার"),
        skill("eye_doctor", "health", "nearby eye doctor", "eye hospital near me", "chokher doctor", "চোখের ডাক্তার"),
        skill("nutrition_information", "health", "nutrition information", "food nutrition facts", "pushti tothyo", "পুষ্টির তথ্য"),
        skill("sleep_guidance", "health", "healthy sleep guidance", "sleep tips", "ghum er tips", "ঘুমের পরামর্শ"),

        // Travel & nearby — 16..30
        skill("bus_schedule", "travel", "local bus schedule", "bus schedule", "bus time", "বাসের সময়সূচি"),
        skill("train_schedule", "travel", "Bangladesh train schedule", "train schedule", "train time", "ট্রেনের সময়সূচি"),
        skill("flight_status", "travel", "flight status", "flight schedule", "flight update", "ফ্লাইট স্ট্যাটাস"),
        skill("route_planner", "travel", "route planner", "best route", "jawar rasta", "যাওয়ার রাস্তা"),
        skill("traffic_status", "travel", "current traffic status", "traffic update", "jam kemon", "রাস্তার যানজট"),
        skill("nearby_fuel", "travel", "nearby fuel station", "petrol pump near me", "fuel station", "কাছের পেট্রোল পাম্প"),
        skill("nearby_parking", "travel", "nearby parking", "parking near me", "car parking", "কাছের পার্কিং"),
        skill("toll_information", "travel", "road toll information", "toll rate", "bridge toll", "টোল রেট"),
        skill("hotel_search", "travel", "nearby hotel", "hotel near me", "hotel booking info", "কাছের হোটেল"),
        skill("restaurant_search", "travel", "nearby restaurant", "restaurant near me", "kacher restaurant", "কাছের রেস্টুরেন্ট"),
        skill("public_toilet", "travel", "nearby public toilet", "public washroom near me", "washroom nearby", "কাছের পাবলিক টয়লেট"),
        skill("car_repair", "travel", "nearby car repair", "garage near me", "mechanic near me", "কাছের গ্যারেজ"),
        skill("ride_fare", "travel", "current ride fare estimate", "ride fare", "taxi fare", "ভাড়ার হিসাব"),
        skill("distance_between", "travel", "distance between places", "distance from", "koto dur", "কত দূর"),
        skill("travel_weather", "travel", "destination weather forecast", "travel weather", "tour weather", "ভ্রমণের আবহাওয়া"),

        // Household & shopping — 31..45
        skill("grocery_price", "household", "current grocery prices", "grocery price", "bazar dor", "বাজার দর"),
        skill("price_compare", "household", "compare product prices", "price comparison", "dam compare", "দাম তুলনা"),
        skill("product_reviews", "household", "product reviews", "review dekhao", "product review", "পণ্যের রিভিউ"),
        skill("recipe_search", "household", "cooking recipe", "recipe", "rannar recipe", "রান্নার রেসিপি"),
        skill("appliance_manual", "household", "appliance user manual", "user manual", "manual khujho", "ব্যবহারের ম্যানুয়াল"),
        skill("electrician", "household", "nearby electrician", "electrician near me", "current mistri", "ইলেকট্রিশিয়ান"),
        skill("plumber", "household", "nearby plumber", "plumber near me", "panir mistri", "প্লাম্বার"),
        skill("ac_repair", "household", "nearby AC repair", "ac service", "ac mechanic", "এসি সার্ভিস"),
        skill("cleaning_service", "household", "nearby home cleaning service", "cleaning service", "home cleaner", "বাসা পরিষ্কার সার্ভিস"),
        skill("laundry", "household", "nearby laundry service", "laundry near me", "dry cleaner", "লন্ড্রি সার্ভিস"),
        skill("gas_service", "household", "local gas emergency service", "gas service", "gas complaint", "গ্যাস সেবা"),
        skill("electricity_outage", "household", "local electricity outage update", "power outage", "current nai", "বিদ্যুৎ আপডেট"),
        skill("water_service", "household", "local water service information", "water complaint", "pani service", "পানি সেবা"),
        skill("courier_tracking", "household", "courier parcel tracking", "parcel tracking", "courier track", "পার্সেল ট্র্যাক"),
        skill("postal_code", "household", "postal code lookup", "post code", "zip code", "পোস্ট কোড"),

        // Education & work — 46..60
        skill("dictionary", "education", "dictionary meaning", "word meaning", "mane ki", "শব্দের অর্থ"),
        skill("translation", "education", "translate text", "translation", "banglay onubad", "অনুবাদ করো"),
        skill("grammar_check", "education", "grammar check", "sentence correction", "grammar thik koro", "ব্যাকরণ ঠিক করো"),
        skill("math_formula", "education", "mathematics formula", "math formula", "goniter sutro", "গণিতের সূত্র"),
        skill("science_explanation", "education", "science topic explanation", "science explanation", "biggan bujhao", "বিজ্ঞান বুঝিয়ে দাও"),
        skill("exam_schedule", "education", "exam schedule", "exam date", "porikkhar routine", "পরীক্ষার রুটিন"),
        skill("exam_result", "education", "exam result", "result check", "porikkhar result", "পরীক্ষার ফলাফল"),
        skill("scholarship", "education", "current scholarship opportunities", "scholarship", "britti tothyo", "বৃত্তির তথ্য"),
        skill("admission_info", "education", "admission information", "admission circular", "vorti tothyo", "ভর্তি তথ্য"),
        skill("school_college", "education", "nearby school college", "school near me", "college near me", "কাছের স্কুল কলেজ"),
        skill("job_search", "work", "current job circular", "job search", "chakrir khobor", "চাকরির খবর"),
        skill("cv_template", "work", "CV resume template", "cv template", "resume sample", "সিভি নমুনা"),
        skill("interview_prep", "work", "job interview preparation", "interview questions", "interview tips", "ইন্টারভিউ প্রস্তুতি"),
        skill("email_template", "work", "professional email template", "email sample", "office email", "ইমেইল নমুনা"),
        skill("coding_help", "work", "programming coding help", "coding tutorial", "code error help", "কোডিং সাহায্য"),

        // Financial & market information only — 61..70
        skill("exchange_rate", "finance_info", "current exchange rate", "dollar rate", "currency rate", "ডলারের রেট"),
        skill("gold_price", "finance_info", "current gold price Bangladesh", "gold price", "sonar dam", "সোনার দাম"),
        skill("fuel_price", "finance_info", "current fuel price Bangladesh", "fuel price", "petrol price", "জ্বালানির দাম"),
        skill("stock_quote", "finance_info", "current stock market quote", "share price", "stock quote", "শেয়ারের দাম"),
        skill("tax_rules", "finance_info", "current Bangladesh tax rules", "income tax rules", "tax information", "কর নিয়ম"),
        skill("vat_rules", "finance_info", "current Bangladesh VAT rules", "vat rules", "vat information", "ভ্যাট নিয়ম"),
        skill("bank_hours", "finance_info", "bank opening hours", "bank time", "bank holiday", "ব্যাংক খোলার সময়"),
        skill("inflation_rate", "finance_info", "current Bangladesh inflation rate", "inflation rate", "mudrasfiti", "মুদ্রাস্ফীতি"),
        skill("market_price", "finance_info", "current Bangladesh market prices", "daily market price", "ajker bazar dor", "আজকের বাজার দর"),
        skill("budget_template", "finance_info", "personal budget template", "monthly budget", "budget plan", "মাসিক বাজেট"),

        // Civic, emergency & faith — 71..80
        skill("prayer_time", "faith", "local prayer times", "namazer shomoy", "prayer time", "নামাজের সময়"),
        skill("qibla_direction", "faith", "qibla direction", "qibla compass", "kibla kon dike", "কিবলা কোন দিকে"),
        skill("nearby_mosque", "faith", "nearby mosque", "mosque near me", "kacher mosjid", "কাছের মসজিদ"),
        skill("public_holiday", "civic", "Bangladesh public holiday calendar", "public holiday", "sorkari chuti", "সরকারি ছুটি"),
        skill("government_office", "civic", "nearby government office", "government service office", "sorkari office", "সরকারি অফিস"),
        skill("passport_info", "civic", "Bangladesh passport application information", "passport application", "passport info", "পাসপোর্ট আবেদন"),
        skill("nid_info", "civic", "Bangladesh NID service information", "nid correction", "national id info", "এনআইডি তথ্য"),
        skill("birth_registration", "civic", "Bangladesh birth registration information", "birth certificate", "jonmo nibondhon", "জন্ম নিবন্ধন"),
        skill("police_station", "emergency", "nearby police station", "police station near me", "thana kothay", "কাছের থানা"),
        skill("fire_service", "emergency", "local fire service emergency number", "fire service number", "fire station", "ফায়ার সার্ভিস"),

        // Digital help & safety — 81..90
        skill("speed_test", "digital", "internet speed test", "wifi speed test", "net speed", "ইন্টারনেট স্পিড টেস্ট"),
        skill("network_outage", "digital", "local network outage status", "internet outage", "network down update", "নেটওয়ার্ক সমস্যা"),
        skill("app_help", "digital", "app help guide", "app tutorial", "app use kivabe", "অ্যাপ ব্যবহারের নিয়ম"),
        skill("phone_manual", "digital", "phone model user manual", "phone manual", "mobile manual", "ফোনের ম্যানুয়াল"),
        skill("scam_check", "safety", "online scam check guidance", "scam check", "fraud check", "প্রতারণা যাচাই"),
        skill("fact_check", "safety", "fact check", "news verification", "sotto kina", "সত্যতা যাচাই"),
        skill("website_safety", "safety", "website safety check", "is this website safe", "link safe kina", "ওয়েবসাইট নিরাপদ কিনা"),
        skill("data_breach", "safety", "data breach information", "email breach check", "account leak info", "ডাটা লিক তথ্য"),
        skill("privacy_guide", "safety", "online privacy guide", "privacy settings guide", "privacy tips", "গোপনীয়তা গাইড"),
        skill("cybercrime_report", "safety", "Bangladesh cyber crime reporting", "cybercrime report", "online harassment report", "সাইবার ক্রাইম রিপোর্ট"),

        // Lifestyle & planning — 91..100
        skill("sunrise_sunset", "lifestyle", "local sunrise sunset time", "sunrise time", "sunset time", "সূর্যোদয় সূর্যাস্ত"),
        skill("air_quality", "lifestyle", "local air quality index", "air quality", "aqi today", "বায়ুর মান"),
        skill("pollen_forecast", "lifestyle", "local pollen forecast", "pollen count", "allergy forecast", "অ্যালার্জি পূর্বাভাস"),
        skill("local_events", "lifestyle", "local events today", "events near me", "ajker event", "আজকের অনুষ্ঠান"),
        skill("movie_showtime", "lifestyle", "nearby movie showtimes", "cinema showtime", "movie time", "সিনেমার শো টাইম"),
        skill("sports_fixture", "lifestyle", "current sports fixtures", "match schedule", "sports schedule", "খেলার সময়সূচি"),
        skill("book_recommendation", "lifestyle", "book recommendations", "books to read", "boi suggest", "বইয়ের পরামর্শ"),
        skill("workout_guide", "lifestyle", "beginner workout guide", "home workout", "exercise plan", "ব্যায়াম গাইড"),
        skill("meditation_guide", "lifestyle", "guided meditation", "meditation for beginners", "mindfulness guide", "মেডিটেশন গাইড"),
        skill("gardening_guide", "lifestyle", "home gardening guide", "plant care", "gach er jotno", "গাছের যত্ন"),
    )

    init {
        check(skills.size == 100) { "DailySkillRegistry must contain exactly 100 skills, found ${skills.size}" }
        check(skills.map { it.id }.toSet().size == skills.size) { "Daily skill ids must be unique" }
    }

    fun resolve(rawText: String): Match? {
        val normalized = NuvaDateTimeParser.normalize(rawText)
        if (normalized.length !in 2..300) return null
        val hit = skills.asSequence()
            .flatMap { skill -> skill.aliases.asSequence().map { alias -> Triple(skill, alias, alias.length) } }
            .filter { (_, alias, _) -> containsAlias(normalized, alias) }
            .maxByOrNull { it.third }
            ?: return null
        val skill = hit.first
        val alias = hit.second
        val query = if (normalized.length <= alias.length + 8) skill.defaultQuery else normalized
        return Match(skill, query.take(300))
    }

    private fun containsAlias(text: String, alias: String): Boolean {
        if (alias.any { it.code in 0x0980..0x09FF }) return text.contains(alias)
        return Regex("""(?<![a-z0-9])${Regex.escape(alias)}(?![a-z0-9])""").containsMatchIn(text)
    }
}
