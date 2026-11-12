-- DEV and test only: an in-memory table with the name and the three columns of the coverage view, so the service
-- runs with no database. In every other profile the view is the real one and this file is never read.
create table member_coverage_rel_v (
    member_id varchar(20) not null,
    eff_date  date        not null,
    end_date  date        not null
);
