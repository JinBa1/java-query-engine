SELECT Student.B, COUNT(*), MIN(Student.C), MAX(Student.C), AVG(Student.C) FROM Student GROUP BY Student.B;
