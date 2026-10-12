-- The start page finds the rules that name a caller with one query. The query compares subjects with the array
-- overlap operator: subjects && <the subjects of the caller>. A B-tree index cannot serve that operator, but a GIN
-- index can.
create index rule_subjects on rule using gin (subjects);
