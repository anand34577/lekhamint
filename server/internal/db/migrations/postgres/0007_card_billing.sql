-- Kosh schema v7: billing cycle of credit cards.
--
-- statement_day is the day of the month the statement is generated and
-- due_day the day payment is due (1-31; short months use their last day).

ALTER TABLE accounts ADD COLUMN statement_day INTEGER;
ALTER TABLE accounts ADD COLUMN due_day INTEGER;
