# CRE Take-Home: Research and Decision

Written in simplified technical English (ASD-STE100 style). Prepared 2026-10-03. Deadline: Monday, October 5, 11:59 PM ET.

The build spec is in `option-a-research.md`.

## 1. Short answer

**Build the loan early-warning tool (option A), with a narrow scope.** The first draft had 12 rules, 3 screens, and 400 loans. That is too much. The new scope has 5 rules, 2 screens, and 100 loans.

## 2. What I learned

### Why loans matter in commercial real estate

- A commercial loan funds an office, a shop, or an apartment building.
- Most of these loans end with a large final payment. The borrower must repay it or take a new loan (a refinance).
- A servicer collects payments and watches each loan for investors. Servicing fees repeat every month. This income is steady, so firms want to grow it.
- A broker finds a new lender for a borrower and earns a fee. A weak loan is often a job for a broker.

### Why now

| Fact | Plain meaning | Source |
| --- | --- | --- |
| $76.6B of CMBS loans reach their end date in 2026. | A large wave of loans needs repayment or a new loan this year. | [CRE Daily](https://www.credaily.com/briefs/cmbs-maturity-wall-tests-refinancing-in-2026/) |
| About 36% of them have a debt yield below 8%. | About 1 in 3 earns too little for a lender to refinance easily. | Same |
| Loans that refinanced averaged 13% to 14% debt yield. Loans that failed averaged about 9%. | Debt yield is a simple, strong warning sign. | Same |
| Office loans in CMBS pools: 12.34% are late (record, January 2026). | About 1 in 8 office loans is behind on payments. | Same |

### How servicers watch loans today

- Each month the servicer gets a standard report (CREFC IRP) for each loan.
- Analysts compare the report to fixed limits on the watchlist. They write a comment on the main risk. ([Freddie Mac guide](https://mf.freddiemac.com/docs/surveillance.pdf))
- The work is monthly and uses many spreadsheets.
- The fixed limits warn late. A loan can slide for months and stay above the line.

**The gap:** no tool watches the direction of change. This is the problem the tool solves.

## 3. Options I compared

Scores run from 1 to 5. A higher number is better.

| Option | What it does | Impact | Effort fit | Data | Fit with my skills | Difference from existing tools |
| --- | --- | --- | --- | --- | --- | --- |
| **A. Loan early-warning tool** | Finds risky loans early and ranks them. | 5 | 4 | 4 | 5 | 4 |
| B. Work-order dispatch | Sorts building repair requests and assigns them. | 3 | 5 | 2 | 5 | 2 |
| C. Lease date extraction | Reads lease PDFs and lists key dates. | 4 | 4 | 2 | 3 | 2 |

Why not B: mature tools exist. It repeats past work and shows little CRE learning.

Why not C: many vendors exist. It needs an LLM. I cannot prove accuracy on made-up leases.

I dropped two more ideas. Underwriting automation has many vendors. A comps database depends on data that large firms own.

## 4. How I decided what to build

I asked one question: what is the smallest tool that proves the idea?

The idea has three claims:

1. The tool warns earlier than the standard watchlist.
2. The tool finds risks the watchlist does not.
3. The tool shows why each alert fired.

I kept only what proves these claims. I cut the rest.

| Kept | Cut | Reason |
| --- | --- | --- |
| 5 rules | 7 rules | 5 rules cover the three claims. |
| Ranked queue and loan detail | Broker view, comment form | These do not prove a claim. |
| 100 loans, 3 planted scenarios | 400 loans, 5 scenarios | 3 scenarios prove the three claims. |
| DSCR and debt yield | Loan-to-value, refinance gap | Two numbers are easier to explain. |
| 3 alert states | Escalation | Escalation adds code but no proof. |

## 5. Interview talking points

### Why this problem, and who it serves

- Servicers watch loans with fixed limits once a month. A loan can slide for months before the alarm rings.
- A $76.6B wave of loans ends in 2026. Early warning has real value now.
- The main user is a servicer analyst. A debt broker is a second user for later.

### What I learned from research

- Debt yield separates loans that refinance from loans that fail (13% to 14% against about 9%).
- The standard watchlist uses fixed lines. It does not watch the direction of change.
- Servicing is steady monthly income. A tool that protects it is worth building.

### How I decided what was worth building

- I scored three options on impact, effort, data, fit, and difference.
- I chose A. Then I cut the scope to the smallest tool that proves three claims (section 4).

### Key assumptions and tradeoffs

- Data is synthetic. The tool proves the mechanics, not the limits.
- Rules, not machine learning. Rules are easy to explain and need no default history.
- Monthly files or messages, not a real-time feed. The source data is monthly.
- A folder poller, not Kafka. The poller needs no broker. Kafka is future scope.
- We use NOI, not net cash flow, to keep DSCR simple.

### Architecture and technical decisions

- One Java service. Monthly reports become events in an append-only log.
- Rules live in a versioned config file. Each alert stores the rule version and inputs.
- Re-importing a file changes nothing. A replay gives the same alerts.
- A folder poller reads new files and starts the ingest step. It does the work that a Kafka consumer would do. ShedLock on a MySQL database makes sure only one node polls at a time.
- Intake is safe to repeat. The event key is loan ID plus month, so a second delivery changes nothing.

### What I built

Fill in after the build. List: data generator, event log, 5 rules, ranked queue, loan detail, tests, and the result of each success check in section 11 of the spec.

### What I would change or build next

1. Test the rules on real SEC loan data.
2. Add a rule for large tenants whose leases end soon.
3. Add a broker view for loans with a refinance gap.
4. Add Kafka as a second intake, then move the event log to Kafka.
5. Add escalation for alerts that no one acknowledges.

## 6. Sources

- [CRE Daily: CMBS maturity wall tests refinancing in 2026](https://www.credaily.com/briefs/cmbs-maturity-wall-tests-refinancing-in-2026/)
- [CREFC IRP Watchlist Implementation Guideline](https://css.crefc.org/uploadedfiles/CMSA_Site_Home/Industry_Standards/CMSA-Investor_Reporting_Package/CREFC_IRP_Watchlist_Implementation_Guideline.pdf)
- [CREFC Investor Reporting Package 7.0](https://css.crefc.org/uploadedfiles/CMSA_Site_Home/Industry_Standards/Investor_Reporting_Package/US/IRP_70/IRP_7_0_Draft.pdf)
- [Freddie Mac multifamily surveillance guide](https://mf.freddiemac.com/docs/surveillance.pdf)
- [Public example of refinance-risk scoring from SEC data](https://github.com/ZacharyChai/cre-credit-risk)
