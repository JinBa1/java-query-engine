SELECT Student.A, Student.B, Enrolled.E, Enrolled.H
FROM Student, Enrolled
WHERE Student.A = Enrolled.A AND Student.D > 30;
