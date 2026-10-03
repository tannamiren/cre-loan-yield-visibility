# Loan Early-Warning Tool: Specification

Written in simplified technical English (ASD-STE100 style). Use this file as the goal for implementation.

## 1. Goal

Build a small tool that finds risky commercial real estate loans early. It must warn months before the standard monthly watchlist does.

The tool must do four things:

1. Receive monthly loan reports from a watched folder.
2. Check each loan against five rules.
3. Put each warning (alert) in a ranked list.
4. Show the numbers that caused each alert.

**Design limit:** Do not use an LLM or machine learning. Each alert must trace to a rule, an input value, and a source.

## 2. The problem in plain words

A commercial loan is a loan to buy an office, a shop, or an apartment building. A company called a servicer collects the payments. The servicer also watches each loan for the investors who own it.

Think of a smoke detector. Today, the servicer's alarm rings only when a number crosses a fixed line. The alarm is late. A loan can get worse for months before it crosses the line.

Most commercial loans do not repay in full by the end date. The borrower must pay a large final amount or get a new loan. A new lender asks if the building earns enough to support the new loan. If the answer is no, the borrower can fail to refinance.

The tool watches for change. It warns when a loan moves toward trouble, not only when it arrives there.

**Who uses it:** a servicer analyst who reviews loans each month. The analyst needs a short, ranked list with the evidence for each item.

## 3. Words you need

| Term | Plain meaning |
| --- | --- |
| Maturity | The date the loan ends. |
| NOI (net operating income) | Yearly rent and other income, minus running costs. |
| DSCR (debt service coverage ratio) | NOI divided by yearly loan payments. 1.00 means income just covers payments. 1.25 is comfortable. |
| Debt yield | NOI divided by the loan balance. It is like the yearly return on the loan amount. Below 8%, lenders often refuse to refinance. |
| Watchlist | The list of loans a servicer must watch closely. A standard industry group (CREFC) sets the limits. |

**Example:** A building has NOI of $2.0M. The yearly loan payments are $1.6M. DSCR is 2.0 divided by 1.6, which is 1.25. The loan balance is $25M. Debt yield is 2.0 divided by 25, which is 8%.

## 4. Data

The tool uses made-up (synthetic) data. The field names match the real industry report format (CREFC IRP). A loader for real data can come later.

- **Size:** 100 loans, 24 months each.
- **Property types:** apartments (most), office, retail.
- **Generation:** each loan follows a seeded random walk. The same seed gives the same data.

| Table | Fields |
| --- | --- |
| `loan` | loan_id, property_type, original_balance, rate, maturity_date, underwriting_noi |
| `loan_month` | loan_id, month (YYYY-MM), balance, noi, yearly_payments, payments_late |

### How the tool calculates the numbers

Use these steps for each loan and each month:

1. **Yearly payments (generator):** the generator sets the monthly payment from the original balance, the rate, and a 30-year payback schedule. Yearly payments equal 12 times the monthly payment. For an interest-only loan, use balance times rate.
2. **NOI (generator):** start at `underwriting_noi`. Each month, add a small random change. The planted scenarios set their own trend.
3. **DSCR (engine):** NOI divided by yearly payments.
4. **Debt yield (engine):** NOI divided by balance.
5. **Months to maturity (engine):** months from the report month to `maturity_date`.

Use BigDecimal and round DSCR and debt yield to 4 decimal places.

Worked example: NOI is $2,000,000. Yearly payments are $1,600,000. DSCR is 1.25. Balance is $25,000,000. Debt yield is 0.08.

### Where the data comes from

The generator writes one CSV file for each month. The columns match the `loan_month` fields. The ingest step reads only this CSV format. It does not know how the data was made.

An optional SEC importer can write the same CSV format from real ABS-EE filings. It maps the ABS-EE balance, NOI, and payment fields to the CSV columns. Check the field names in the SEC schema when you build it. The importer is not in scope now. The cloud sandbox cannot reach SEC EDGAR, so the importer must run on a local machine.

### How reports enter the tool (intake)

A folder poller reads the monthly reports. A scheduled job checks the `inbox/` folder. When a new CSV file appears, the job starts the ingest process for that file. The poller does the work that a Kafka consumer would do. Kafka is future scope.

#### Poller steps

1. The scheduled job runs every 10 seconds. It asks ShedLock for the lock `report-poller`. If another node holds the lock, skip this run.
2. List the `*.csv` files in `inbox/`. Skip a file that changed in the last 5 seconds, because it may still be in a write.
3. Move one file to `processing/`. A move is atomic, so only one node gets the file.
4. Read each row and call `IngestService.ingest(row)`.
5. On success, move the file to `done/`. On error, move it to `failed/` and write the error to the log.

#### ShedLock

ShedLock makes sure that only one node runs a scheduled job at a time. This lets you run many copies of the service.

- Use the JDBC lock provider on the same MySQL database. ShedLock needs one table named `shedlock`.
- Set `lockAtMostFor` to 5 minutes, so a crashed node cannot hold the lock for ever.
- Set `lockAtLeastFor` to 5 seconds.

#### Rules for intake

- `IngestService` writes an event through the `EventLog` interface. The event key is loan ID plus month.
- A repeat of the same key does nothing. So a file that arrives twice is safe.
- The rules and alerts steps read only from the event log. They do not know how the data arrived.
- Kafka later: add a Kafka consumer that calls the same `IngestService.ingest(row)`. No other step changes.

### Three planted scenarios

Each scenario has one known correct result. Tests use them.

1. **Slow slide.** DSCR falls from 1.45 to 1.05 over 12 months. Rule R4 must fire at least 3 months before rule R2.
2. **Weak refinance.** DSCR stays healthy. Debt yield is 7%. Maturity is 9 months away. Rule R5 must fire. Rule R2 must stay silent.
3. **Missed payments.** The loan is 2 payments late, then pays in full. Rule R1 must fire and later resolve.

## 5. Rules

Each rule is data in a config file. A rule has an ID, a condition, a type, and a source. All limits are configurable. Each config change gets a version number. Each alert records the version.

| ID | Name | Fires when | Type | Source |
| --- | --- | --- | --- | --- |
| R1 | Late payments | 2 or more payments are late. | Credit | CREFC watchlist code 1A |
| R2 | Low DSCR | DSCR is below 1.10. | Credit | CREFC code 1E |
| R3 | Maturity soon | Maturity is within 90 days. | Credit | CREFC code 5A |
| R4 | DSCR falling | DSCR fell 0.15 or more in 6 months. | Early warning | Our design |
| R5 | Low debt yield near maturity | Debt yield is below 8% and maturity is within 18 months. | Early warning | Our design, from [CRE Daily](https://www.credaily.com/briefs/cmbs-maturity-wall-tests-refinancing-in-2026/) data |

R1 to R3 copy the standard watchlist. R4 and R5 are our early-warning rules. They need testing on real data before anyone relies on them.

## 6. Alerts and score

- One alert belongs to one loan and one rule.
- If a later month fires the same rule, update the alert. Do not create a duplicate.
- Alert states: **Open**, **Acknowledged**, **Resolved**.
- An alert resolves by itself after 2 clear months in a row.

The score is the sum of three parts. Show the parts next to each alert.

| Part | Points |
| --- | --- |
| Type | Credit 40. Early warning 25. |
| Time to maturity | Within 6 months: 30. 6 to 12 months: 20. 12 to 18 months: 10. Longer: 0. |
| Loan size | 0 to 20. Scale by balance against the largest loan. |

## 7. Screens and API

| Screen | Content |
| --- | --- |
| Queue | Open alerts, ranked by score. Each row shows the loan, the rule, the score, and an Acknowledge button. |
| Loan detail | A 24-month chart of DSCR and debt yield with alert marks. A list of alerts with their input values, the limit, and the rule version. |

API calls:

- `GET /alerts` returns the ranked queue.
- `GET /loans/{id}` returns one loan with its history and alerts.
- `POST /alerts/{id}/acknowledge` sets the state to Acknowledged.

## 8. Architecture

Build one Java service. Monthly reports become events. Each later step reads the events and can replay them.

Flow: data generator, monthly report file, folder poller, ingest, event log, metrics, rules, alerts, API, screens.

| Decision | Choice | Reason |
| --- | --- | --- |
| Language | Java 21, Spring Boot 3 | Strong typing for money logic. |
| Numbers | BigDecimal with set rounding | No floating-point error. |
| Storage | MySQL 8 in Docker Compose. H2 (MySQL mode) for tests. | Easy to install. One command to run. ShedLock supports it. |
| Event log | Append-only table behind an `EventLog` interface | Replay and audit. Kafka can replace the table later. |
| Intake | Folder poller | No broker to run. A new file starts the process, like a message would. |
| Distributed use | ShedLock on the MySQL database | Many nodes can run. Only one polls at a time. |
| Repeat safety | Event key is loan ID plus month | A second delivery of the same file does nothing. |
| Rules | YAML config. Plain Java rule classes. | Five rules do not need a rules framework. |
| Audit | Each alert stores rule ID, rule version, and input values | You can explain any alert without a re-run. |
| Screens | Server-rendered Thymeleaf pages | One deployable unit. |
| Tests | One test per rule. One test per planted scenario. | Proves the rules and the full flow. |

## 9. Assumptions and tradeoffs

### Assumptions

- The servicer gets one report per loan each month.
- We use NOI to calculate DSCR. The standard uses a close value called net cash flow.
- The limits in section 5 are defaults. A real servicer sets its own.
- One user role. Real login is out of scope.

### Tradeoffs

| Decision | Chosen | Not chosen | Reason |
| --- | --- | --- | --- |
| Method | Rules with a simple score | Machine learning | Each alert is easy to explain. No default history is needed. |
| Data | Synthetic | Real SEC filings | Runs anywhere. Has known answers. |
| Processing | Monthly files or messages in, events inside | Real-time market feed | The source data is monthly. Events give replay and audit. |
| Intake | Folder poller with ShedLock | Kafka consumer | No broker to run. Same ingest step, so Kafka can be added later. |
| Scope | 5 rules, 2 screens | Full watchlist, broker view | Fits the deadline. Proves the idea. |

### Risks

- **Too many alerts.** The queue becomes noise. The score ranks alerts. The tool reports open alerts per 100 loans each month so you can tune limits.
- **Synthetic data proves the mechanics, not the limits.** Test R4 and R5 on real data later.

## 10. Scope

### Build for the deadline

- [ ] Data generator with three planted scenarios. Seeded.
- [ ] Ingest monthly reports as events into an append-only log.
- [ ] Folder poller with ShedLock.
- [ ] Docker Compose with MySQL.
- [ ] Calculate DSCR and debt yield.
- [ ] Rule engine with R1 to R5. Limits in versioned config.
- [ ] Alerts with no duplicates, a score with visible parts, and the three states.
- [ ] REST API with the three calls in section 7.
- [ ] Two screens: queue and loan detail.
- [ ] Tests: one per rule. One per planted scenario.
- [ ] README: problem, research, assumptions, tradeoffs, architecture, next steps.

### Do not build

Broker view, tenant-lease rules, refinance gap, loan-to-value, alert escalation, analyst comment form, real data import, Kafka, and login.

## 11. How to judge success

| Check | Pass condition |
| --- | --- |
| Early | In scenario 1, R4 fires at least 3 months before R2. |
| Gap in the standard list | In scenario 2, R5 fires and R2 stays silent. |
| Clear | Every alert shows the rule, the inputs, and the limit. |
| Quiet | Fewer than 15 open alerts per 100 loans in a normal month. |
| Repeatable | The same seed gives the same alerts. |
| Safe intake | Drop the same file twice. The alerts do not change. |
| One poller | Run two nodes on one inbox. One node processes each file. |

## 12. Next steps after the deadline

1. Test the rules on real SEC loan data. Measure how many months before a default each rule fired.
2. Add a rule for large tenants whose leases end soon.
3. Add a view for debt brokers that lists loans with a refinance gap.
4. Add Kafka. Add a consumer that calls the same ingest step. Later, move the event log to a Kafka topic and run each step as its own consumer.
5. Add an alert escalation step for unacknowledged credit alerts.
