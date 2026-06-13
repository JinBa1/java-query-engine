SELECT Staff.dept, MIN(Staff.name), MAX(Staff.salary), COUNT(*) FROM Staff GROUP BY Staff.dept;
