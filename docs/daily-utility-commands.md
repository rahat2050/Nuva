# NUVA v1.8 — Daily utility command pack

NUVA does not pretend that 1,000 hard-coded sentences are 1,000 independent features. The local
utility engine is slot-based: source unit × target unit × language × phrase form. Its conservative
catalogue count is over 1,000 supported command forms, with the result calculated offline.

## Calculation examples

- `2 + 3 * 4 koto`
- `calculate (2 + 3) * 4`
- `500 theke 200 biyog koro`
- `sqrt 81 koto`, `12 squared`, `factorial 5`
- `500 er 20 percent koto`
- `500 er 20 percent discount`
- `500 er 15 percent VAT add`
- `1000 bill 10 percent tip 4 jon e split`

## Daily planning and health

- `shopping list e add koro dim dudh`, then `shopping list dekhao`
- `expense note lunch 250`, then `khoroch gulo poro`
- `todo list poro`, `note gulo dekhao`
- `weight 70 kg height 170 cm BMI koto`
- `100000 loan 12 percent 2 year EMI koto`
- `300 km 20 liter mileage koto`
- `born 2000 age koto`
- `2026-12-16 koto din baki`
- `difference between 2026-08-01 and 2026-08-16`
- `2026-08-26 ki bar`
- `coin toss`, `roll dice`, `random number 10 theke 20`
- `average of 10 20 30`, `median 9 1 5 3`, `ratio 20 30`
- `10000 simple interest 10 percent 2 year`
- `buying price 500 selling price 650 profit`
- `savings goal 12000 in 6 months monthly save`
- `120 km 60 kmph travel time koto`
- `trip fuel cost 300 km 15 km/l 130 taka per liter`
- `rectangle area 10 5`, `circle area radius 7`
- `male 70 kg 175 cm 30 year BMR`, `70 kg daily water intake`
- `1 GB 100 Mbps download time`

BMI/BMR/water are screening estimates, not medical advice. EMI is approximate and excludes bank fees.
Expense logging only saves a local note; it never enters or automates a financial transaction.

## Conversion dimensions

- length: millimeter, centimeter, meter, kilometer, inch, foot, yard, mile
- weight: milligram, gram, kilogram, tonne, ounce, pound, stone
- cooking volume: milliliter, liter, teaspoon, tablespoon, cup, pint, gallon
- area/BD land: square meter, square foot, decimal/shotok, katha, bigha, acre, hectare
- speed: m/s, km/h, mph, ft/s, knot
- time: millisecond, second, minute, hour, day, week, month, year
- data: byte, KB, MB, GB, TB, PB
- energy: joule, kilojoule, food calorie, watt-hour
- pressure: pascal, kPa, bar, PSI, atmosphere
- temperature: Celsius, Fahrenheit, Kelvin

Examples: `5 kilometer mile e koto`, `2 katha square foot e koto`,
`100 fahrenheit to celsius`, `1 cup milliliter e koto`, `1 gigabyte megabyte e koto`.

## Sourced web information

Current weather, news, scores, traffic, rates, prices, prayer times, sunrise/sunset, air quality and
transport schedules open a live search. Factual/how-to, recipe, definition, translation, nearby,
health-information, education and job questions also open a sourced result instead of returning an
unsupported response or inventing an answer.
