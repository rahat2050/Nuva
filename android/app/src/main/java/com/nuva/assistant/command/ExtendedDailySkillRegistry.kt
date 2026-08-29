package com.nuva.assistant.command

/**
 * Five hundred additional sourced skills generated from practical entity × task matrices.
 *
 * Unlike a flat list of near-duplicate phrases, every generated skill has two
 * independently matched slots (for example `washing_machine × repair` or
 * `passport × required_documents`). A command must contain both an entity and
 * a task alias, which keeps matching precise while allowing Bangla, Banglish
 * and English wording plus user details such as a model number or location.
 */
object ExtendedDailySkillRegistry {

    const val EXPECTED_SKILL_COUNT = 500

    data class Skill(
        val id: String,
        val category: String,
        val defaultQuery: String,
        val entityId: String,
        val taskId: String,
    )

    data class Match(val skill: Skill, val query: String)

    private data class Axis(val id: String, val label: String, val aliases: List<String>)
    private data class Matrix(val category: String, val entities: List<Axis>, val tasks: List<Axis>)
    private data class AxisHit(val axis: Axis, val alias: String)

    private fun entity(id: String, label: String, vararg aliases: String) =
        Axis(id, label, (listOf(label) + aliases).map { it.lowercase() }.distinct())

    private fun task(id: String, label: String, vararg aliases: String) =
        Axis(id, label, (listOf(label) + aliases).map { it.lowercase() }.distinct())

    // 25 entities × 8 tasks = 200 local-service skills.
    private val serviceEntities = listOf(
        entity("pediatrician", "pediatrician", "child doctor", "shishu doctor", "শিশু ডাক্তার"),
        entity("gynecologist", "gynecologist", "women doctor", "gynae doctor", "গাইনি ডাক্তার"),
        entity("physiotherapist", "physiotherapist", "physio", "ফিজিওথেরাপিস্ট"),
        entity("dermatologist", "dermatologist", "skin doctor", "chormo doctor", "চর্মরোগ ডাক্তার"),
        entity("veterinarian", "veterinarian", "vet doctor", "poshu doctor", "পশু ডাক্তার"),
        entity("legal_aid", "legal aid lawyer", "lawyer", "ukil", "আইনজীবী", "উকিল"),
        entity("notary", "notary public", "notary", "নোটারি"),
        entity("atm", "ATM booth", "atm", "cash machine", "এটিএম বুথ"),
        entity("post_office", "post office", "dakghor", "ডাকঘর"),
        entity("courier_office", "courier office", "courier branch", "কুরিয়ার অফিস"),
        entity("mobile_service", "mobile service center", "phone service center", "মোবাইল সার্ভিস সেন্টার"),
        entity("computer_repair", "computer repair shop", "computer service", "কম্পিউটার সার্ভিস"),
        entity("phone_repair", "phone repair shop", "mobile repair", "মোবাইল মেরামত"),
        entity("tailor", "tailor shop", "darji", "দর্জি"),
        entity("barber", "barber shop", "salon for men", "নাপিত", "বারবার শপ"),
        entity("beauty_salon", "beauty salon", "parlour", "পার্লার", "বিউটি সেলুন"),
        entity("gym", "gym fitness center", "gym", "জিম"),
        entity("swimming_pool", "swimming pool", "pool", "সুইমিং পুল"),
        entity("library", "public library", "library", "পাঠাগার", "লাইব্রেরি"),
        entity("coworking", "coworking space", "shared office", "কোওয়ার্কিং স্পেস"),
        entity("daycare", "daycare center", "child care", "ডে কেয়ার"),
        entity("private_tutor", "private tutor", "home tutor", "গৃহশিক্ষক", "টিউটর"),
        entity("photographer", "photographer", "photo studio", "ফটোগ্রাফার"),
        entity("event_venue", "event venue", "community hall", "অনুষ্ঠানের হল"),
        entity("community_center", "community center", "কমিউনিটি সেন্টার"),
    )

    private val serviceTasks = listOf(
        task("nearby", "nearby", "near me", "kache", "কাছে", "কাছের"),
        task("directions", "directions", "route", "jawar rasta", "যাওয়ার রাস্তা"),
        task("contact", "contact number", "phone number", "number dao", "ফোন নম্বর"),
        task("hours", "opening hours", "open time", "kokhon khole", "কখন খোলে"),
        task("cost", "service cost", "fee", "khoroch koto", "খরচ কত", "ফি কত"),
        task("reviews", "customer reviews", "review", "রিভিউ"),
        task("appointment", "appointment information", "booking information", "serial", "অ্যাপয়েন্টমেন্ট"),
        task("availability", "available today", "today availability", "aj pawa jabe", "আজ পাওয়া যাবে"),
    )

    // 20 entities × 5 tasks = 100 government/education service skills.
    private val publicEntities = listOf(
        entity("passport", "Bangladesh passport", "passport", "পাসপোর্ট"),
        entity("nid", "Bangladesh NID", "national ID", "nid", "এনআইডি"),
        entity("birth_registration", "birth registration", "birth certificate", "jonmo nibondhon", "জন্ম নিবন্ধন"),
        entity("driving_license", "Bangladesh driving license", "driving licence", "ড্রাইভিং লাইসেন্স"),
        entity("vehicle_registration", "vehicle registration", "car registration", "গাড়ির রেজিস্ট্রেশন"),
        entity("tin", "Bangladesh TIN certificate", "tin certificate", "টিন সার্টিফিকেট"),
        entity("police_clearance", "police clearance certificate", "police clearance", "পুলিশ ক্লিয়ারেন্স"),
        entity("trade_license", "trade license", "business license", "ট্রেড লাইসেন্স"),
        entity("land_record", "Bangladesh land record", "khatian", "porcha", "খতিয়ান", "পর্চা"),
        entity("land_mutation", "Bangladesh land mutation", "namjari", "mutation", "নামজারি"),
        entity("holding_tax", "holding tax", "হোল্ডিং ট্যাক্স"),
        entity("electricity_connection", "electricity connection", "new electricity line", "বিদ্যুৎ সংযোগ"),
        entity("gas_connection", "gas connection", "new gas line", "গ্যাস সংযোগ"),
        entity("water_connection", "water connection", "new water line", "পানি সংযোগ"),
        entity("school_admission", "school admission", "স্কুল ভর্তি"),
        entity("university_admission", "university admission", "বিশ্ববিদ্যালয় ভর্তি"),
        entity("scholarship_service", "scholarship application", "britti", "বৃত্তি আবেদন"),
        entity("pension", "Bangladesh pension service", "pension", "পেনশন"),
        entity("social_allowance", "Bangladesh social allowance", "ভাতা", "সামাজিক ভাতা"),
        entity("voter_registration", "Bangladesh voter registration", "voter info", "ভোটার নিবন্ধন"),
    )

    private val publicTasks = listOf(
        task("process", "application process", "apply kivabe", "abedon prokriya", "আবেদন প্রক্রিয়া"),
        task("eligibility", "eligibility requirements", "joggota", "যোগ্যতা"),
        task("documents", "required documents", "ki kagoj lagbe", "documents needed", "কী কাগজ লাগবে"),
        task("fees", "official fees", "fee koto", "সরকারি ফি", "ফি কত"),
        task("help", "status and help", "status check", "helpline", "অবস্থা যাচাই", "সহায়তা"),
    )

    // 20 subjects × 5 tasks = 100 learning skills.
    private val learningEntities = listOf(
        entity("english", "English language", "english", "ইংরেজি"),
        entity("bangla", "Bangla language", "bangla", "বাংলা"),
        entity("mathematics", "mathematics", "math", "gonit", "গণিত"),
        entity("physics", "physics", "podarthobiggan", "পদার্থবিজ্ঞান"),
        entity("chemistry", "chemistry", "roshayon", "রসায়ন"),
        entity("biology", "biology", "jibbiggan", "জীববিজ্ঞান"),
        entity("ict", "ICT", "information technology", "আইসিটি"),
        entity("programming", "computer programming", "coding", "programming", "প্রোগ্রামিং"),
        entity("excel", "Microsoft Excel", "excel", "এক্সেল"),
        entity("word", "Microsoft Word", "ms word", "মাইক্রোসফট ওয়ার্ড"),
        entity("powerpoint", "Microsoft PowerPoint", "powerpoint", "পাওয়ারপয়েন্ট"),
        entity("accounting", "accounting", "hisabbiggan", "হিসাববিজ্ঞান"),
        entity("economics", "economics", "orthoniti", "অর্থনীতি"),
        entity("statistics", "statistics", "porisongkhan", "পরিসংখ্যান"),
        entity("graphic_design", "graphic design", "graphics design", "গ্রাফিক ডিজাইন"),
        entity("digital_marketing", "digital marketing", "ডিজিটাল মার্কেটিং"),
        entity("public_speaking", "public speaking", "presentation skill", "পাবলিক স্পিকিং"),
        entity("interview_english", "job interview English", "interview english", "ইন্টারভিউ ইংরেজি"),
        entity("driving_theory", "driving theory", "traffic rules", "ড্রাইভিং থিওরি"),
        entity("first_aid_course", "first aid course", "প্রাথমিক চিকিৎসা কোর্স"),
    )

    private val learningTasks = listOf(
        task("beginner", "beginner guide", "for beginners", "shuru theke", "শুরু থেকে"),
        task("tutorial", "step by step tutorial", "tutorial", "ধাপে ধাপে"),
        task("examples", "worked examples", "example", "udahoron", "উদাহরণ"),
        task("practice", "practice exercises", "practice", "onushilon", "অনুশীলনী"),
        task("reference", "cheat sheet reference", "formula sheet", "quick reference", "সংক্ষিপ্ত নোট"),
    )

    // 20 products × 5 tasks = 100 household product skills.
    private val productEntities = listOf(
        entity("washing_machine", "washing machine", "ওয়াশিং মেশিন"),
        entity("refrigerator", "refrigerator", "fridge", "ফ্রিজ"),
        entity("air_conditioner", "air conditioner", "ac", "এয়ার কন্ডিশনার"),
        entity("microwave", "microwave oven", "microwave", "মাইক্রোওয়েভ"),
        entity("rice_cooker", "rice cooker", "রাইস কুকার"),
        entity("blender", "kitchen blender", "blender", "ব্লেন্ডার"),
        entity("water_purifier", "water purifier", "পানি ফিল্টার"),
        entity("television", "television", "smart tv", "টেলিভিশন"),
        entity("router", "Wi-Fi router", "router", "রাউটার"),
        entity("laptop", "laptop computer", "laptop", "ল্যাপটপ"),
        entity("smartphone", "smartphone", "mobile phone", "স্মার্টফোন"),
        entity("printer", "printer", "প্রিন্টার"),
        entity("inverter", "IPS inverter", "ips", "inverter", "আইপিএস"),
        entity("solar_panel", "solar panel", "সোলার প্যানেল"),
        entity("bicycle", "bicycle", "cycle", "সাইকেল"),
        entity("motorcycle", "motorcycle", "motorbike", "মোটরসাইকেল"),
        entity("car", "car", "private car", "গাড়ি"),
        entity("sewing_machine", "sewing machine", "সেলাই মেশিন"),
        entity("electric_fan", "electric fan", "ceiling fan", "ফ্যান"),
        entity("induction_cooker", "induction cooker", "ইন্ডাকশন চুলা"),
    )

    private val productTasks = listOf(
        task("price", "price comparison", "current price", "dam koto", "দাম কত"),
        task("reviews", "product reviews", "review", "রিভিউ"),
        task("buying_guide", "buying guide", "which one to buy", "kenar guide", "কেনার গাইড"),
        task("manual", "user manual", "setup guide", "ব্যবহারের নিয়ম", "ম্যানুয়াল"),
        task("repair", "repair troubleshooting", "not working", "problem fix", "মেরামত", "সমস্যা সমাধান"),
    )

    private val matrices = listOf(
        Matrix("local_service", serviceEntities, serviceTasks),
        Matrix("public_service", publicEntities, publicTasks),
        Matrix("learning", learningEntities, learningTasks),
        Matrix("product_help", productEntities, productTasks),
    )

    val skills: List<Skill> = matrices.flatMap { matrix ->
        matrix.entities.flatMap { entity ->
            matrix.tasks.map { task ->
                Skill(
                    id = "${matrix.category}_${entity.id}_${task.id}",
                    category = matrix.category,
                    defaultQuery = "${entity.label} ${task.label}",
                    entityId = entity.id,
                    taskId = task.id,
                )
            }
        }
    }

    init {
        check(skills.size == EXPECTED_SKILL_COUNT) {
            "ExtendedDailySkillRegistry must contain $EXPECTED_SKILL_COUNT skills, found ${skills.size}"
        }
        check(skills.map { it.id }.toSet().size == skills.size) { "Extended daily skill ids must be unique" }
    }

    fun resolve(rawText: String): Match? {
        val normalized = NuvaDateTimeParser.normalize(rawText)
        if (normalized.length !in 3..300) return null
        val candidates = matrices.mapNotNull { matrix ->
            val entityHit = bestHit(normalized, matrix.entities) ?: return@mapNotNull null
            val taskHit = bestHit(normalized, matrix.tasks) ?: return@mapNotNull null
            Triple(matrix, entityHit, taskHit)
        }
        val winner = candidates.maxByOrNull { (_, entityHit, taskHit) -> entityHit.alias.length + taskHit.alias.length }
            ?: return null
        val matrix = winner.first
        val entity = winner.second.axis
        val task = winner.third.axis
        val skillId = "${matrix.category}_${entity.id}_${task.id}"
        val skill = skills.first { it.id == skillId }
        val matchedLength = winner.second.alias.length + winner.third.alias.length
        val query = if (normalized.length <= matchedLength + 10) skill.defaultQuery else normalized
        return Match(skill, query.take(300))
    }

    private fun bestHit(text: String, axes: List<Axis>): AxisHit? = axes.asSequence()
        .flatMap { axis -> axis.aliases.asSequence().map { alias -> AxisHit(axis, alias) } }
        .filter { containsAlias(text, it.alias) }
        .maxByOrNull { it.alias.length }

    private fun containsAlias(text: String, alias: String): Boolean {
        if (alias.any { it.code in 0x0980..0x09FF }) return text.contains(alias)
        return Regex("""(?<![a-z0-9])${Regex.escape(alias)}(?![a-z0-9])""").containsMatchIn(text)
    }
}
