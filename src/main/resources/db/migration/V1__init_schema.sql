CREATE TABLE "loan" (
    "loan_id" VARCHAR(20) NOT NULL PRIMARY KEY,
    "property_type" VARCHAR(20) NOT NULL,
    "original_balance" DECIMAL(15,2) NOT NULL,
    "rate" DECIMAL(6,4) NOT NULL,
    "maturity_date" DATE NOT NULL,
    "underwriting_noi" DECIMAL(15,2) NOT NULL
);

CREATE TABLE "loan_month_event" (
    "id" BIGINT AUTO_INCREMENT PRIMARY KEY,
    "loan_id" VARCHAR(20) NOT NULL,
    "month" VARCHAR(7) NOT NULL,
    "balance" DECIMAL(15,2) NOT NULL,
    "noi" DECIMAL(15,2) NOT NULL,
    "yearly_payments" DECIMAL(15,2) NOT NULL,
    "payments_late" INT NOT NULL,
    "received_at" TIMESTAMP NOT NULL,
    CONSTRAINT "uq_loan_month" UNIQUE ("loan_id", "month")
);

CREATE TABLE "alert" (
    "id" BIGINT AUTO_INCREMENT PRIMARY KEY,
    "loan_id" VARCHAR(20) NOT NULL,
    "rule_id" VARCHAR(10) NOT NULL,
    "state" VARCHAR(20) NOT NULL,
    "score" INT NOT NULL,
    "score_type" INT NOT NULL,
    "score_time" INT NOT NULL,
    "score_size" INT NOT NULL,
    "rule_version" INT NOT NULL,
    "fired_month" VARCHAR(7) NOT NULL,
    "clear_months_count" INT NOT NULL DEFAULT 0,
    "inputs_json" VARCHAR(1000) NOT NULL,
    "limit_value" VARCHAR(100) NOT NULL,
    "created_at" TIMESTAMP NOT NULL,
    "updated_at" TIMESTAMP NOT NULL,
    CONSTRAINT "uq_loan_rule" UNIQUE ("loan_id", "rule_id")
);

CREATE TABLE "shedlock" (
    "name" VARCHAR(64) NOT NULL PRIMARY KEY,
    "lock_until" TIMESTAMP(3) NOT NULL,
    "locked_at" TIMESTAMP(3) NOT NULL,
    "locked_by" VARCHAR(255) NOT NULL
);
