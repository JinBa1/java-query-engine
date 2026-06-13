SELECT Student.B, SUM(Student.C)
FROM Student
WHERE Student.D > 20
GROUP BY Student.B;
