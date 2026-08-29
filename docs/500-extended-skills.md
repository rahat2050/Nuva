# NUVA v2.0 — 500 precise sourced skills

This pack adds **exactly 500** generated skills. Each command must match both an entity and a task,
so a lone word such as `passport` or `repair` does not trigger anything. Details such as location,
brand, model and error code remain in the search query. All results are read-only sourced web
lookups; financial transactions and credentials are blocked before matching.

## Matrix A — local services: 25 × 8 = 200

### Entities (25)
Pediatrician, gynecologist, physiotherapist, dermatologist, veterinarian, lawyer/legal aid, notary,
ATM, post office, courier office, mobile service centre, computer repair, phone repair, tailor,
barber, beauty salon, gym, swimming pool, library, coworking space, daycare, private tutor,
photographer, event venue and community centre.

### Tasks (8 per entity)
Nearby search, directions, contact number, opening hours, service cost, customer reviews,
appointment/booking information and availability today.

Examples:
- `nearby private tutor`
- `uttara private tutor contact number`
- `darji kokhon khole`
- `ফিজিওথেরাপিস্ট ফোন নম্বর`
- `mobile service center customer reviews`

## Matrix B — public services: 20 × 5 = 100

### Entities (20)
Passport, NID, birth registration, driving licence, vehicle registration, TIN, police clearance,
trade licence, land record/khatian/porcha, mutation/namjari, holding tax, electricity connection,
gas connection, water connection, school admission, university admission, scholarship, pension,
social allowance and voter registration.

### Tasks (5 per entity)
Application process, eligibility, required documents, official fees and status/help/helpline.

Examples:
- `passport ki kagoj lagbe`
- `NID correction official fees`
- `নামজারি আবেদন প্রক্রিয়া`
- `driving license eligibility requirements`
- `birth registration status check`

## Matrix C — learning: 20 × 5 = 100

### Subjects (20)
English, Bangla, mathematics, physics, chemistry, biology, ICT, programming, Excel, Word,
PowerPoint, accounting, economics, statistics, graphic design, digital marketing, public speaking,
interview English, driving theory and first aid.

### Tasks (5 per subject)
Beginner guide, step-by-step tutorial, worked examples, practice exercises and cheat-sheet/reference.

Examples:
- `excel tutorial`
- `physics worked examples`
- `গণিত অনুশীলনী`
- `programming beginner guide`
- `accounting formula sheet`

## Matrix D — product help: 20 × 5 = 100

### Products (20)
Washing machine, refrigerator, air conditioner, microwave, rice cooker, blender, water purifier,
television, router, laptop, smartphone, printer, IPS/inverter, solar panel, bicycle, motorcycle, car,
sewing machine, electric fan and induction cooker.

### Tasks (5 per product)
Price comparison, reviews, buying guide, user/setup manual and repair/troubleshooting.

Examples:
- `washing machine repair`
- `LG F4 washing machine repair error UE`
- `রাউটার ব্যবহারের নিয়ম`
- `laptop current price`
- `solar panel buying guide`

## Count and safety invariants

- Local services: 200
- Public services: 100
- Learning: 100
- Product help: 100
- **Total: 500 unique IDs**
- Entity + task are both mandatory
- Query length is capped
- Server/AI cannot execute these as local-only actions; they resolve to the existing safe web-search action
- Payment, transfer, OTP, PIN and password policies run before either skill registry
