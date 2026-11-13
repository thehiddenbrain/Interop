-- DEV and test only: the coverage rows of the stub members (mmi-stub/members.json), one row per continuous coverage
-- period of each member id, an open end as 12/31/3999. A converted member has rows under each of its two ids.
-- Keep in step with the stub members when a member is added or its coverage changes.
insert into member_coverage_rel_v (member_id, eff_date, end_date) values ('123456789   01', DATE '2021-01-01', DATE '3999-12-31');
insert into member_coverage_rel_v (member_id, eff_date, end_date) values ('S98765432   01', DATE '2021-01-01', DATE '3999-12-31');
insert into member_coverage_rel_v (member_id, eff_date, end_date) values ('234567890   01', DATE '2023-01-01', DATE '2025-12-31');
insert into member_coverage_rel_v (member_id, eff_date, end_date) values ('T20262026   01', DATE '2026-01-01', DATE '2026-12-31');
insert into member_coverage_rel_v (member_id, eff_date, end_date) values ('U20252026   01', DATE '2025-01-01', DATE '2026-12-31');
insert into member_coverage_rel_v (member_id, eff_date, end_date) values ('34567890101', DATE '2025-01-01', DATE '3999-12-31');
insert into member_coverage_rel_v (member_id, eff_date, end_date) values ('34567890102', DATE '2023-01-01', DATE '2024-05-31');
insert into member_coverage_rel_v (member_id, eff_date, end_date) values ('34567890102', DATE '2025-01-01', DATE '3999-12-31');
insert into member_coverage_rel_v (member_id, eff_date, end_date) values ('34567890103', DATE '2023-01-01', DATE '2024-12-31');
insert into member_coverage_rel_v (member_id, eff_date, end_date) values ('HP456789012', DATE '2013-03-01', DATE '3999-12-31');
insert into member_coverage_rel_v (member_id, eff_date, end_date) values ('567890123   01', DATE '2020-01-01', DATE '2024-12-31');
insert into member_coverage_rel_v (member_id, eff_date, end_date) values ('HP567890123', DATE '2025-01-01', DATE '3999-12-31');
insert into member_coverage_rel_v (member_id, eff_date, end_date) values ('678901234   01', DATE '2023-01-01', DATE '2025-06-30');
insert into member_coverage_rel_v (member_id, eff_date, end_date) values ('789012345   01', DATE '2027-01-01', DATE '3999-12-31');
insert into member_coverage_rel_v (member_id, eff_date, end_date) values ('890123456   01', DATE '2025-01-01', DATE '3999-12-31');
insert into member_coverage_rel_v (member_id, eff_date, end_date) values ('901234567   01', DATE '2022-01-01', DATE '3999-12-31');
insert into member_coverage_rel_v (member_id, eff_date, end_date) values ('V67659951   01', DATE '2020-01-01', DATE '2023-12-31');
-- the member the stub returns beside an error message (StubMmiClient.FAULT_ERROR_WITH_MEMBER)
insert into member_coverage_rel_v (member_id, eff_date, end_date) values ('887777777   01', DATE '2024-01-01', DATE '3999-12-31');
